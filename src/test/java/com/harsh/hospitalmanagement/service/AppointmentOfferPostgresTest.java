package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.security.LocalAccount;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "HMS_APPOINTMENT_TEST_DATABASE", matches = ".+")
@DataJpaTest(properties = "spring.sql.init.mode=never", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AppointmentService.class, AppointmentSlotService.class, DoctorAvailabilityService.class,
        DoctorLocationService.class, DoctorManagementAccess.class, AuditLogService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// Let the service proxies commit independently so each assertion reloads persisted state.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentOfferPostgresTest {

    private static final DoctorLocationService.ClinicDetails ORIGINAL_CLINIC =
            clinic("Original clinic", "123 Main Road", "20.00");
    private static final DoctorLocationService.ClinicDetails EDITED_CLINIC =
            clinic("Updated clinic", "456 New Road", "35.00");

    @Autowired private AppointmentService appointments;
    @Autowired private AppointmentSlotService slots;
    @Autowired private DoctorAvailabilityService availability;
    @Autowired private DoctorLocationService locations;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private final LocalDate day = LocalDate.now().plusDays(2);
    private Fixture fixture;

    @DynamicPropertySource
    static void disposablePostgres(DynamicPropertyRegistry registry) {
        String database = System.getenv("HMS_APPOINTMENT_TEST_DATABASE");
        if (database == null || !database.matches("hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}")) {
            throw new IllegalArgumentException("A disposable reconciliation database name is required");
        }
        String password = System.getenv("HMS_APPOINTMENT_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("HMS_APPOINTMENT_TEST_PASSWORD is required for PostgreSQL");
        }
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/" + database);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeEach
    void createFixtures() {
        assertThat(AopUtils.isAopProxy(appointments)).isTrue();
        assertThat(AopUtils.isAopProxy(slots)).isTrue();
        assertThat(AopUtils.isAopProxy(availability)).isTrue();
        assertThat(AopUtils.isAopProxy(locations)).isTrue();
        fixture = transaction(() -> {
            Specialization specialization = new Specialization();
            specialization.setName("Appointment offer test " + UUID.randomUUID());
            entityManager.persist(specialization);

            Doctor doctor = new Doctor();
            doctor.setSpecialization(specialization);
            doctor.setVerificationStatus(VerificationStatus.VERIFIED);
            doctor.setUser(createUser(Role.DOCTOR));
            entityManager.persist(doctor);

            Patient patient = new Patient();
            patient.setFirstName("Test");
            patient.setLastName("Patient");
            patient.setUser(createUser(Role.PATIENT));
            entityManager.persist(patient);
            return new Fixture(doctor.getId(), patient.getId(), specialization.getId(),
                    account(doctor.getUser()), account(patient.getUser()));
        });
    }

    @AfterEach
    void removeFixtures() {
        if (fixture == null) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.update("delete from audit_logs where performed_by_email in (?, ?)",
                    fixture.doctor().email(), fixture.patient().email());
            jdbc.update("delete from appointments where doctor_id = ? and patient_id = ?",
                    fixture.doctorId(), fixture.patientId());
            jdbc.update("delete from appointment_slots where doctor_id = ?", fixture.doctorId());
            jdbc.update("delete from doctor_availability where doctor_id = ?", fixture.doctorId());
            jdbc.update("delete from doctor_locations where doctor_id = ?", fixture.doctorId());
            jdbc.update("delete from patients where id = ?", fixture.patientId());
            jdbc.update("delete from doctors where id = ?", fixture.doctorId());
            jdbc.update("delete from users where id in (?, ?)", fixture.doctor().id(), fixture.patient().id());
            jdbc.update("delete from specializations where id = ?", fixture.specializationId());
        });
    }

    @Test
    void staleVersionIsRejectedAndReplacementVersionBooksTheSameSlot() {
        Offer offer = createOffer();
        locations.update(fixture.doctorId(), offer.clinicId(), EDITED_CLINIC, doctorAuthentication());
        AppointmentSlot replacement = generateSlot(offer.availabilityId());
        assertThat(replacement.getId()).isEqualTo(offer.slot().getId());
        assertThat(replacement.getOfferVersion()).isNotNull().isNotEqualTo(offer.slot().getOfferVersion());

        assertThatThrownBy(() -> book(offer.slot()))
                .isInstanceOfSatisfying(ConflictException.class,
                        error -> assertThat(error.getCode()).isEqualTo("OFFER_CHANGED"));
        assertThat(jdbc.queryForObject("select count(*) from appointments where slot_id = ?", Long.class,
                replacement.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where performed_by_email = ?", Long.class,
                fixture.patient().email())).isZero();
        assertThat(slots.getDiscoverableOffers(fixture.doctorId(), day))
                .extracting(AppointmentSlot::getOfferVersion).containsExactly(replacement.getOfferVersion());

        Appointment booked = book(replacement);
        assertThat(jdbc.queryForList("select id from appointments where slot_id = ?", Long.class, replacement.getId()))
                .containsExactly(booked.getId());
        Snapshot stored = reloadBooking(booked.getId(), offer);
        assertThat(stored).isEqualTo(new Snapshot(offer.clinicId(), ConsultationMode.IN_CLINIC,
                EDITED_CLINIC.clinicName(), "456 New Road, Central, Pune, Maharashtra, 411001",
                new BigDecimal("35.00"), replacement.getOfferVersion()));
    }

    @Test
    void clinicEditAndRejectedSqlUpdatesLeaveTheBookingSnapshotUnchanged() {
        Offer offer = createOffer();
        long appointmentId = book(offer.slot()).getId();
        Snapshot original = reloadBooking(appointmentId, offer);
        assertThat(original).isEqualTo(new Snapshot(offer.clinicId(), ConsultationMode.IN_CLINIC,
                ORIGINAL_CLINIC.clinicName(), "123 Main Road, Central, Pune, Maharashtra, 411001",
                new BigDecimal("20.00"), offer.slot().getOfferVersion()));

        locations.update(fixture.doctorId(), offer.clinicId(), EDITED_CLINIC, doctorAuthentication());
        transaction(() -> {
            var clinic = entityManager.find(DoctorLocation.class, offer.clinicId());
            assertThat(clinic.getClinicName()).isEqualTo(EDITED_CLINIC.clinicName());
            assertThat(clinic.getAddressLine1()).isEqualTo(EDITED_CLINIC.addressLine1());
            assertThat(clinic.getInClinicFee()).isEqualByComparingTo("35.00");
            return null;
        });
        assertThat(reloadBooking(appointmentId, offer)).isEqualTo(original);

        long otherClinicId = locations.create(fixture.doctorId(),
                clinic("Other clinic", "789 Side Road", "50.00"), doctorAuthentication()).getId();
        rejectSnapshotUpdate("update appointments set location_id = ? where id = ?", otherClinicId, appointmentId);
        rejectSnapshotUpdate("update appointments set clinic_name_snapshot = ? where id = ?", "Tampered clinic", appointmentId);
        rejectSnapshotUpdate("update appointments set clinic_address_snapshot = ? where id = ?", "Tampered address", appointmentId);
        rejectSnapshotUpdate("update appointments set consultation_fee = ? where id = ?", new BigDecimal("99.00"), appointmentId);
        rejectSnapshotUpdate("update appointments set offer_version = ? where id = ?", UUID.randomUUID(), appointmentId);
        // Use a complete video snapshot so the alternative would satisfy the row's mode checks.
        rejectSnapshotUpdate("""
                update appointments set consultation_mode = 'VIDEO', location_id = null,
                    clinic_name_snapshot = null, clinic_address_snapshot = null where id = ?
                """, appointmentId);
        assertThat(reloadBooking(appointmentId, offer)).isEqualTo(original);
    }

    private Offer createOffer() {
        long clinicId = locations.create(fixture.doctorId(), ORIGINAL_CLINIC, doctorAuthentication()).getId();
        var request = new DoctorAvailabilityRequest();
        request.setDoctorId(fixture.doctorId());
        request.setDate(day);
        request.setStartTime(LocalTime.of(9, 0));
        request.setEndTime(LocalTime.of(9, 30));
        request.setConsultationMode(ConsultationMode.IN_CLINIC);
        request.setLocationId(clinicId);
        long availabilityId = availability.createAvailability(request, fixture.doctor(), false).getId();
        AppointmentSlot slot = generateSlot(availabilityId);
        assertThat(slot.getOfferVersion()).isNotNull();
        return new Offer(clinicId, availabilityId, slot);
    }

    private AppointmentSlot generateSlot(long availabilityId) {
        var generated = slots.generateSlots(availabilityId, fixture.doctor(), false);
        assertThat(generated).hasSize(1);
        return generated.getFirst();
    }

    private Appointment book(AppointmentSlot slot) {
        return appointments.bookVersionedOffer(slot.getId(), day, slot.getOfferVersion(),
                "PostgreSQL integration test", fixture.patient());
    }

    private Snapshot reloadBooking(long appointmentId, Offer offer) {
        return transaction(() -> {
            var booked = entityManager.find(Appointment.class, appointmentId);
            assertThat(booked.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
            assertThat(booked.getDoctor().getId()).isEqualTo(fixture.doctorId());
            assertThat(booked.getPatient().getId()).isEqualTo(fixture.patientId());
            var slot = booked.getSlot();
            assertThat(slot.getId()).isEqualTo(offer.slot().getId());
            assertThat(slot.getDoctor().getId()).isEqualTo(fixture.doctorId());
            assertThat(slot.getAvailability().getId()).isEqualTo(offer.availabilityId());
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
            return new Snapshot(booked.getLocation().getId(), booked.getConsultationMode(),
                    booked.getClinicNameSnapshot(), booked.getClinicAddressSnapshot(),
                    booked.getConsultationFee(), booked.getOfferVersion());
        });
    }

    private void rejectSnapshotUpdate(String sql, Object... parameters) {
        assertThatThrownBy(() -> jdbc.update(sql, parameters))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getMessage()).contains("Appointment offer snapshot is immutable");
                });
    }

    private UsernamePasswordAuthenticationToken doctorAuthentication() {
        var principal = new LocalAccount.PasswordPrincipal(fixture.doctor(), "unused", Role.DOCTOR);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private User createUser(Role role) {
        User user = new User();
        user.setEmail("appointment-offer-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }

    private static LocalAccount account(User user) {
        return new LocalAccount(user.getId(), user.getEmail());
    }

    private <T> T transaction(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(ignored -> operation.get());
    }

    private static DoctorLocationService.ClinicDetails clinic(String name, String street, String fee) {
        return new DoctorLocationService.ClinicDetails(name, street, null, "Central",
                "Pune", "Maharashtra", "411001", "0205550100", new BigDecimal(fee), true);
    }

    private record Fixture(long doctorId, long patientId, long specializationId,
                           LocalAccount doctor, LocalAccount patient) {}

    private record Offer(long clinicId, long availabilityId, AppointmentSlot slot) {}

    private record Snapshot(long locationId, ConsultationMode mode, String clinicName,
                            String clinicAddress, BigDecimal fee, UUID offerVersion) {}
}
