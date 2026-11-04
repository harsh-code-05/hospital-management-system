package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.controller.AppointmentController;
import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2,
        replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({DoctorAvailabilityService.class, AppointmentSlotService.class, AppointmentService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduleReconciliationTest {

    private static final LocalAccount ADMIN = new LocalAccount(0, "admin@test.invalid");
    private final LocalDate day = LocalDate.now().plusDays(2);

    @Autowired
    private DoctorAvailabilityService availabilityService;
    @Autowired
    private AppointmentSlotService slotService;
    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private AppointmentSlotRepository slotRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private AuditLogService auditLogService;

    @ParameterizedTest
    @ValueSource(strings = {"shrink", "shift", "date", "reassign"})
    void editRetiresOldSlotsAndPreservesAppointmentViews(String edit) {
        Doctor otherDoctor = doctor();
        Doctor originalDoctor = doctor();
        Patient patient = patient();
        DoctorAvailability original = create(originalDoctor, "09:00", "10:30");
        List<AppointmentSlot> slots = generate(original);
        AppointmentSlot booked = slots.getFirst();
        AppointmentSlot unbooked = slots.getLast();
        Appointment appointment = book(patient, originalDoctor, booked);

        Doctor target = edit.equals("reassign") ? otherDoctor : originalDoctor;
        LocalDate targetDate = edit.equals("date") ? day.plusDays(1) : day;
        String start = edit.equals("shift") ? "09:15" : "09:00";
        String end = edit.equals("shrink") ? "10:00" : "10:30";
        DoctorAvailability updated = availabilityService.updateAvailability(original.getId(),
                request(target, targetDate, start, end), ADMIN, true);

        assertThat(updated.getRevision()).isEqualTo(1);
        assertThat(slotService.getAvailableSlots(originalDoctor.getId(), day)).isEmpty();
        assertThat(slotService.getAvailableSlots(originalDoctor.getId(), null)).isEmpty();
        assertThat(slotService.getSlotById(booked.getId())).satisfies(stored -> {
            assertThat(stored.isRetired()).isTrue();
            assertThat(stored.getStatus()).isEqualTo(SlotStatus.BOOKED);
            assertThat(stored.getAvailabilityRevision()).isZero();
        });
        assertThat(slotService.getSlotById(unbooked.getId())).satisfies(stored -> {
            assertThat(stored.isRetired()).isTrue();
            assertThat(stored.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        });
        assertThatThrownBy(() -> book(patient, originalDoctor, unbooked))
                .isInstanceOf(BadRequestException.class).hasMessage("Appointment slot is not available");

        AppointmentController controller = new AppointmentController(appointmentService);
        for (User viewer : List.of(patient.getUser(), originalDoctor.getUser())) {
            var principal = new LocalAccount.PasswordPrincipal(account(viewer), "unused", viewer.getRole());
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities());
            assertThat(controller.getAppointments(authentication).getBody()).singleElement().satisfies(response -> {
                assertThat(response.getId()).isEqualTo(appointment.getId());
                assertThat(response.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
                assertThat(response.getDoctor().getId()).isEqualTo(originalDoctor.getId());
                assertThat(response.getSlot().getId()).isEqualTo(booked.getId());
                assertThat(response.getSlot().getDate()).isEqualTo(day);
                assertThat(response.getSlot().getStartTime()).isEqualTo(LocalTime.of(9, 0));
                assertThat(response.getSlot().getEndTime()).isEqualTo(LocalTime.of(9, 30));
            });
        }
    }

    @Test
    void generationIsIdempotentAndReissuesAnExactRetiredSlotOnlyWhenEligible() {
        Doctor doctor = doctor();
        DoctorAvailability original = create(doctor, "09:00", "10:00");
        List<AppointmentSlot> first = generate(original);
        assertThat(generate(original)).isEmpty();

        DoctorAvailability noChange = edit(original, "09:00", "10:00");
        assertThat(noChange.getRevision()).isZero();
        assertThat(slotRepository.isBookable(first.getFirst().getId())).isTrue();

        DoctorAvailability shrunk = edit(original, "09:30", "10:00");
        assertThat(slotRepository.isBookable(first.getLast().getId())).isFalse();
        assertThat(generate(shrunk)).singleElement().satisfies(slot -> {
            assertThat(slot.getId()).isEqualTo(first.getLast().getId());
            assertThat(slot.getAvailabilityRevision()).isEqualTo(1);
            assertThat(slot.isRetired()).isFalse();
        });
        assertThat(generate(shrunk)).isEmpty();
        assertThat(slotRepository.isBookable(first.getFirst().getId())).isFalse();
    }

    @Test
    void shiftedGenerationSkipsOccupiedTimeAndBookingAllowsAdjacentTime() {
        Doctor doctor = doctor();
        Patient patient = patient();
        DoctorAvailability original = create(doctor, "09:00", "10:00");
        AppointmentSlot booked = generate(original).getFirst();
        book(patient, doctor, booked);
        assertThat(generate(edit(original, "09:15", "10:15")))
                .extracting(AppointmentSlot::getStartTime).containsExactly(LocalTime.of(9, 45));

        DoctorAvailability adjacent = edit(original, "09:30", "10:30");
        assertThat(book(patient, doctor, generate(adjacent).getFirst()).getStatus())
                .isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void expectedDateMismatchLeavesNoAppointmentAndKeepsTheSlotAvailable() {
        Doctor doctor = doctor();
        Patient patient = patient();
        DoctorAvailability window = create(doctor, "09:00", "09:30");
        AppointmentSlot slot = generate(window).getFirst();

        assertThatThrownBy(() -> appointmentService.bookAppointment(patient.getId(), doctor.getId(), slot.getId(),
                slot.getDate().plusDays(1), "Test", account(patient.getUser())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Selected appointment date does not match the slot date");

        assertThat(appointmentRepository.findByDoctor_User_IdOrderByCreatedAtDesc(doctor.getUser().getId())).isEmpty();
        assertThat(slotService.getSlotById(slot.getId()).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(slotRepository.isBookable(slot.getId())).isTrue();
        assertThat(book(patient, doctor, slot).getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void generationSkipsIntervalsOverlappingAnExistingBookableSlot() {
        Doctor doctor = doctor();
        DoctorAvailability window = create(doctor, "09:00", "10:00");
        storeOffer(window, "09:15", "09:45");
        assertThat(generate(window)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "revision", "doctor", "date", "bounds"})
    void inconsistentProvenanceCannotBeListedOrBookedById(String mismatch) {
        Doctor doctor = doctor();
        DoctorAvailability window = create(doctor, "09:00", "10:00");
        Doctor slotDoctor = mismatch.equals("doctor") ? doctor() : doctor;
        LocalDate slotDate = mismatch.equals("date") ? day.plusDays(1) : day;
        String start = mismatch.equals("bounds") ? "08:45" : "09:00";
        String end = mismatch.equals("bounds") ? "09:15" : "09:30";
        AppointmentSlot slot = transaction(() -> {
            AppointmentSlot invalid = storeSlot(slotDoctor, slotDate, start, end,
                    mismatch.equals("missing") ? null : window);
            invalid.setRetired(false);
            if (mismatch.equals("revision")) {
                invalid.setAvailabilityRevision(window.getRevision() + 1);
            }
            return invalid;
        });
        Patient patient = patient();

        assertThat(slotService.getAvailableSlots(slotDoctor.getId(), slotDate)).isEmpty();
        assertThatThrownBy(() -> book(patient, slotDoctor, slot))
                .isInstanceOf(BadRequestException.class).hasMessage("Appointment slot is not available");
    }

    @Test
    void legacyCancellationPreservesHistoryAndGenerationCanExplicitlyPublishTheInterval() {
        Doctor doctor = doctor();
        Patient patient = patient();
        DoctorAvailability window = create(doctor, "09:00", "09:30");
        Appointment legacy = transaction(() -> {
            AppointmentSlot slot = storeSlot(doctor, day, "09:00", "09:30", null);
            slot.setStatus(SlotStatus.BOOKED);
            return storeAppointment(patient, slot, AppointmentStatus.CONFIRMED);
        });
        Long slotId = legacy.getSlot().getId();
        assertThat(generate(window)).isEmpty();
        appointmentService.cancelAppointment(legacy.getId(), account(patient.getUser()), "ROLE_PATIENT");
        assertThatThrownBy(() -> book(patient, doctor, legacy.getSlot()))
                .isInstanceOf(BadRequestException.class).hasMessage("Appointment slot is not available");
        assertThat(generate(window)).extracting(AppointmentSlot::getId).containsExactly(slotId);
        assertThat(book(patient, doctor, legacy.getSlot()).getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(appointmentRepository.findById(legacy.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void completionUsesOriginalSlotEndEvenAfterAvailabilityMovesToTheFuture() {
        Doctor doctor = doctor();
        Patient patient = patient();
        LocalDate yesterday = day.minusDays(3);
        Appointment appointment = transaction(() -> {
            DoctorAvailability original = new DoctorAvailability();
            original.setDoctor(doctor);
            original.setDate(yesterday);
            original.setStartTime(LocalTime.of(9, 0));
            original.setEndTime(LocalTime.of(10, 0));
            entityManager.persist(original);
            AppointmentSlot slot = storeSlot(doctor, yesterday, "09:00", "09:30", original);
            slot.setStatus(SlotStatus.BOOKED);
            return storeAppointment(patient, slot, AppointmentStatus.CONFIRMED);
        });
        Long availabilityId = transaction(() ->
                slotRepository.findById(appointment.getSlot().getId()).orElseThrow().getAvailability().getId());
        availabilityService.updateAvailability(availabilityId, request(doctor, day, "11:00", "12:00"), ADMIN, true);
        Appointment completed = appointmentService.completeAppointment(appointment.getId(),
                account(doctor.getUser()), "ROLE_DOCTOR");
        assertThat(completed.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(completed.getSlot()).satisfies(slot -> {
            assertThat(slot.isRetired()).isTrue();
            assertThat(slot.getDate()).isEqualTo(yesterday);
            assertThat(slot.getEndTime()).isEqualTo(LocalTime.of(9, 30));
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        });
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CONFIRMED"}, mode = EnumSource.Mode.EXCLUDE)
    void terminalAppointmentsDoNotCountAsActiveOverlaps(AppointmentStatus terminalStatus) {
        Doctor doctor = doctor();
        Patient patient = patient();
        DoctorAvailability window = create(doctor, "09:00", "10:00");
        transaction(() -> {
            AppointmentSlot historical = storeSlot(doctor, day.minusDays(3), "09:00", "09:30", null);
            historical.setStatus(SlotStatus.BOOKED);
            return storeAppointment(patient, historical, terminalStatus);
        });
        assertThat(appointmentRepository.existsOverlappingConfirmedAppointment(doctor.getId(), day.minusDays(3),
                LocalTime.of(9, 15), LocalTime.of(9, 45))).isFalse();
        assertThat(generate(window)).hasSize(2);
    }

    @Test
    void concurrentOverlappingBookingsOnDifferentSlotIdsLeaveOneConfirmedAppointment() throws Exception {
        Doctor doctor = doctor();
        Patient firstPatient = patient();
        Patient secondPatient = patient();
        DoctorAvailability window = create(doctor, "09:00", "10:00");
        AppointmentSlot first = storeOffer(window, "09:00", "09:30");
        AppointmentSlot second = storeOffer(window, "09:15", "09:45");
        assertThat(first.getId()).isNotEqualTo(second.getId());

        assertThatThrownBy(() -> race(() -> book(firstPatient, doctor, first), () -> book(secondPatient, doctor, second)))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BadRequestException.class)
                .hasRootCauseMessage("Doctor already has an appointment during this time");
        assertThat(appointmentRepository.findByDoctor_User_IdOrderByCreatedAtDesc(doctor.getUser().getId()))
                .extracting(Appointment::getStatus).containsExactly(AppointmentStatus.CONFIRMED);
    }

    @Test
    void bookingWaitsForAnEditAndRejectsTheOldSlot() {
        Doctor doctor = doctor();
        Patient patient = patient();
        DoctorAvailability original = create(doctor, "09:00", "10:00");
        AppointmentSlot slot = generate(original).getFirst();

        assertThatThrownBy(() -> race(() -> edit(original, "11:00", "12:00"), () -> book(patient, doctor, slot)))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BadRequestException.class)
                .hasRootCauseMessage("Appointment slot is not available");
        assertThat(slotRepository.isBookable(slot.getId())).isFalse();
        assertThat(appointmentRepository.findByDoctor_User_IdOrderByCreatedAtDesc(doctor.getUser().getId())).isEmpty();
    }

    @Test
    void generationWaitsForAnEditAndUsesTheNewRevision() throws Exception {
        Doctor doctor = doctor();
        DoctorAvailability original = create(doctor, "09:00", "10:00");
        AppointmentSlot old = generate(original).getFirst();

        List<AppointmentSlot> generated = race(() -> edit(original, "11:00", "12:00"), () -> generate(original));

        assertThat(generated).extracting(AppointmentSlot::getStartTime)
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(11, 30));
        assertThat(generated).allSatisfy(slot -> assertThat(slot.getAvailabilityRevision()).isEqualTo(1));
        assertThat(slotRepository.isBookable(old.getId())).isFalse();
    }

    @Test
    void editWaitsForGenerationAndRetiresTheSlotsJustPublished() throws Exception {
        Doctor doctor = doctor();
        DoctorAvailability original = create(doctor, "09:00", "10:00");

        DoctorAvailability updated = race(() -> assertThat(generate(original)).hasSize(2),
                () -> edit(original, "11:00", "12:00"));

        assertThat(updated.getRevision()).isEqualTo(1);
        assertThat(slotService.getAvailableSlots(doctor.getId(), day)).isEmpty();
        assertThat(slotRepository.findUnretiredForRevision(original.getId(), 0)).isEmpty();
    }

    @Test
    void cancellationWaitsForAnEditAndDoesNotReopenARetiredSlot() throws Exception {
        Doctor doctor = doctor();
        Patient patient = patient();
        DoctorAvailability original = create(doctor, "09:00", "10:00");
        AppointmentSlot slot = generate(original).getFirst();
        Appointment appointment = book(patient, doctor, slot);

        Appointment cancelled = race(() -> edit(original, "11:00", "12:00"), () ->
                appointmentService.cancelAppointment(appointment.getId(), account(patient.getUser()), "ROLE_PATIENT"));

        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getSlot().isRetired()).isTrue();
        assertThat(slotRepository.isBookable(slot.getId())).isFalse();
    }

    @Test
    void concurrentAvailabilityCreationChecksForOverlapsAfterTakingTheDoctorLock() {
        Doctor doctor = doctor();
        assertThatThrownBy(() -> race(() -> create(doctor, "09:00", "10:00"),
                () -> create(doctor, "09:30", "10:30")))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BadRequestException.class)
                .hasRootCauseMessage("Doctor already has availability during this time");
        assertThat(create(doctor, "10:00", "10:30").getId()).isNotNull();
    }

    private <T> T race(Supplier<?> first, Supplier<T> second) throws Exception {
        var firstRan = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var winner = executor.submit(() -> transaction(() -> {
                first.get();
                firstRan.countDown();
                await(releaseFirst);
                return null;
            }));
            await(firstRan);
            var contender = executor.submit(() -> {
                secondStarted.countDown();
                return second.get();
            });
            await(secondStarted);
            assertThrows(TimeoutException.class, () -> contender.get(250, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            winner.get(10, TimeUnit.SECONDS);
            return contender.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the concurrent transaction", exception);
        }
    }

    private <T> T transaction(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(ignored -> operation.get());
    }

    private DoctorAvailability create(Doctor doctor, String start, String end) {
        return availabilityService.createAvailability(request(doctor, day, start, end), account(doctor.getUser()), false);
    }

    private DoctorAvailability edit(DoctorAvailability availability, String start, String end) {
        return availabilityService.updateAvailability(availability.getId(),
                request(availability.getDoctor(), day, start, end), ADMIN, true);
    }

    private DoctorAvailabilityRequest request(Doctor doctor, LocalDate date, String start, String end) {
        DoctorAvailabilityRequest request = new DoctorAvailabilityRequest();
        request.setDoctorId(doctor.getId());
        request.setDate(date);
        request.setStartTime(LocalTime.parse(start));
        request.setEndTime(LocalTime.parse(end));
        return request;
    }

    private List<AppointmentSlot> generate(DoctorAvailability window) {
        return slotService.generateSlots(window.getId(), ADMIN, true);
    }

    private Appointment book(Patient patient, Doctor doctor, AppointmentSlot slot) {
        return appointmentService.bookAppointment(patient.getId(), doctor.getId(), slot.getId(), slot.getDate(), "Test",
                account(patient.getUser()));
    }

    private AppointmentSlot storeOffer(DoctorAvailability window, String start, String end) {
        return transaction(() -> storeSlot(window.getDoctor(), day, start, end, window));
    }

    private AppointmentSlot storeSlot(
            Doctor doctor, LocalDate date, String start, String end, DoctorAvailability availability) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setDoctor(doctor);
        slot.setDate(date);
        slot.setStartTime(LocalTime.parse(start));
        slot.setEndTime(LocalTime.parse(end));
        slot.setStatus(SlotStatus.AVAILABLE);
        if (availability != null) {
            slot.setAvailability(availability);
            slot.setAvailabilityRevision(availability.getRevision());
            slot.setRetired(false);
        }
        entityManager.persist(slot);
        return slot;
    }

    private Appointment storeAppointment(Patient patient, AppointmentSlot slot, AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(slot.getDoctor());
        appointment.setSlot(slot);
        appointment.setStatus(status);
        entityManager.persist(appointment);
        return appointment;
    }

    private Doctor doctor() {
        return transaction(() -> {
            Specialization specialization = new Specialization();
            specialization.setName(UUID.randomUUID().toString());
            entityManager.persist(specialization);
            Doctor doctor = new Doctor();
            doctor.setUser(user(Role.DOCTOR));
            doctor.setSpecialization(specialization);
            entityManager.persist(doctor);
            return doctor;
        });
    }

    private Patient patient() {
        return transaction(() -> {
            Patient patient = new Patient();
            patient.setFirstName("Test");
            patient.setLastName("Patient");
            patient.setUser(user(Role.PATIENT));
            entityManager.persist(patient);
            return patient;
        });
    }

    private User user(Role role) {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@test.invalid");
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }

    private static LocalAccount account(User user) {
        return new LocalAccount(user.getId(), user.getEmail());
    }
}
