package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2,
        replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(AppointmentService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppointmentConcurrencyTest {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    void cancellationWinsAgainstConcurrentCompletion_withoutCompletingAReleasedSlot() throws Exception {
        assertSerializedTransitions(false, true);
    }

    @Test
    void completionWinsAgainstConcurrentCancellation_withoutReleasingTheOccupiedSlot() throws Exception {
        assertSerializedTransitions(true, false);
    }

    @Test
    void concurrentCancellations_onlyReleaseTheSlotAndAuditOnce() throws Exception {
        assertSerializedTransitions(false, false);
    }

    private void assertSerializedTransitions(boolean winnerCompletes, boolean contenderCompletes) throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long appointmentId = transactions.execute(ignored -> createConfirmedAppointment());
        CountDownLatch winnerHasLock = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<AppointmentStatus> winner = executor.submit(() -> transactions.execute(ignored -> {
                // Hold the schedule lock before starting the competing service call.
                long doctorId = appointmentRepository.findDoctorIdById(appointmentId).orElseThrow();
                doctorRepository.findByIdForUpdate(doctorId).orElseThrow();
                winnerHasLock.countDown();
                await(releaseWinner);
                return transition(appointmentId, winnerCompletes).getStatus();
            }));
            assertThat(winnerHasLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AppointmentStatus> contender = executor.submit(() -> {
                contenderStarted.countDown();
                return transition(appointmentId, contenderCompletes).getStatus();
            });
            assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> contender.get(300, TimeUnit.MILLISECONDS),
                    "The second transition must wait while the first transaction holds the doctor lock");

            releaseWinner.countDown();
            AppointmentStatus expectedStatus = winnerCompletes
                    ? AppointmentStatus.COMPLETED : AppointmentStatus.CANCELLED;
            assertThat(winner.get(10, TimeUnit.SECONDS)).isEqualTo(expectedStatus);
            assertThatThrownBy(() -> contender.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class)
                    .hasRootCauseMessage(contenderCompletes
                            ? "Only confirmed appointments can be completed"
                            : "Only confirmed appointments can be cancelled");

            transactions.executeWithoutResult(ignored -> {
                Appointment stored = appointmentRepository.findById(appointmentId).orElseThrow();
                assertThat(stored.getStatus()).isEqualTo(expectedStatus);
                assertThat(stored.getSlot().getStatus()).isEqualTo(winnerCompletes
                        ? SlotStatus.BOOKED : SlotStatus.AVAILABLE);
            });

            verify(auditLogService, times(1)).logEvent(
                    eq(winnerCompletes ? AuditEventType.ADMIN_COMPLETED_APPOINTMENT
                            : AuditEventType.ADMIN_CANCELLED_APPOINTMENT),
                    eq("admin@test.invalid"), eq("ROLE_ADMIN"), eq("Appointment"),
                    eq(appointmentId), any(), isNull());
            verify(auditLogService, times(1)).logEvent(any(), any(), any(), any(), any(), any(), any());
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Appointment transition(Long appointmentId, boolean completes) {
        return completes
                ? appointmentService.completeAppointment(appointmentId, account("admin@test.invalid"), "ROLE_ADMIN")
                : appointmentService.cancelAppointment(appointmentId, account("admin@test.invalid"), "ROLE_ADMIN");
    }

    private Long createConfirmedAppointment() {
        String suffix = UUID.randomUUID().toString();
        User patientUser = new User();
        patientUser.setEmail("patient-" + suffix + "@test.invalid");
        patientUser.setRole(Role.PATIENT);
        entityManager.persist(patientUser);
        User doctorUser = new User();
        doctorUser.setEmail("doctor-" + suffix + "@test.invalid");
        doctorUser.setRole(Role.DOCTOR);
        entityManager.persist(doctorUser);

        Patient patient = new Patient();
        patient.setFirstName("Test");
        patient.setLastName("Patient");
        patient.setUser(patientUser);
        entityManager.persist(patient);
        Specialization specialization = new Specialization();
        specialization.setName("Test specialization " + suffix);
        entityManager.persist(specialization);
        Doctor doctor = new Doctor();
        doctor.setFirstName("Test");
        doctor.setLastName("Doctor");
        doctor.setUser(doctorUser);
        doctor.setSpecialization(specialization);
        entityManager.persist(doctor);

        AppointmentSlot slot = new AppointmentSlot();
        slot.setDoctor(doctor);
        slot.setDate(LocalDate.now().minusDays(1));
        slot.setStartTime(LocalTime.of(9, 0));
        slot.setEndTime(LocalTime.of(9, 30));
        slot.setStatus(SlotStatus.BOOKED);
        entityManager.persist(slot);
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setSlot(slot);
        appointment.setReason("Concurrency test");
        appointment.setCreatedAt(LocalDateTime.now().minusDays(2));
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        entityManager.persist(appointment);
        entityManager.flush();
        return appointment.getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the winning transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding doctor lock", exception);
        }
    }
}
