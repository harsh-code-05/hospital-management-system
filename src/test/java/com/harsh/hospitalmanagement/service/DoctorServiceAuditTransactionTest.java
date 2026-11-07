package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.repository.AuditLogRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doctor_audit_atomicity;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.sql.init.mode=never"
}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DoctorService.class, AuditLogService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// Let the service proxy own the transaction so assertions see committed database state.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DoctorServiceAuditTransactionTest {

    private static final String ADMIN = "transaction-test-admin@example.invalid";

    @Autowired private DoctorService doctorService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private Specialization specialization;

    // Opt in to a prepared disposable PostgreSQL database. Otherwise Maven uses H2.
    @DynamicPropertySource
    static void disposablePostgres(DynamicPropertyRegistry registry) {
        String database = System.getenv("HMS_DOCTOR_TEST_DATABASE");
        if (database == null) {
            return;
        }
        if (!database.matches("hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}")) {
            throw new IllegalArgumentException("A disposable reconciliation database name is required");
        }
        String password = System.getenv("HMS_DOCTOR_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("HMS_DOCTOR_TEST_PASSWORD is required for PostgreSQL");
        }
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/" + database);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeEach
    void createFixtures() {
        String suffix = UUID.randomUUID().toString();
        User fixtureUser = new User();
        fixtureUser.setEmail("doctor-audit-" + suffix + "@example.invalid");
        fixtureUser.setRole(Role.DOCTOR);
        user = users.save(fixtureUser);

        Specialization fixtureSpecialization = new Specialization();
        fixtureSpecialization.setName("Doctor audit test " + suffix);
        specialization = specializations.save(fixtureSpecialization);
    }

    @AfterEach
    void removeFixtures() {
        if (user != null) {
            jdbc.update("""
                    delete from audit_logs where entity_name = 'Doctor' and
                    (details = ? or entity_id in (select id from doctors where user_id = ?))
                    """, creationAuditDetails(), user.getId());
            jdbc.update("delete from doctors where user_id = ?", user.getId());
            users.deleteById(user.getId());
        }
        if (specialization != null) {
            specializations.deleteById(specialization.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createCommitsProfileAndAudit(boolean useDefaultActor) {
        assertThat(AopUtils.isAopProxy(doctorService)).isTrue();
        Doctor doctor = createDoctor(useDefaultActor);

        assertThat(jdbc.queryForList("select id from doctors where user_id = ?", Long.class, user.getId()))
                .containsExactly(doctor.getId());
        assertThat(auditLogs.findByEntityNameAndEntityIdOrderByTimestampDesc("Doctor", doctor.getId()))
                .singleElement().satisfies(log -> {
                    assertThat(log.getEventType()).isEqualTo(AuditEventType.ADMIN_CREATED_DOCTOR);
                    assertThat(log.getPerformedByEmail()).isEqualTo(useDefaultActor ? "admin" : ADMIN);
                });
    }

    @Test
    void updateCommitsProfileAndAudit() {
        Doctor doctor = createDoctor(false);
        doctorService.updateDoctor(doctor.getId(), updateRequest(), ADMIN);

        assertThat(jdbc.queryForObject("select first_name from doctors where id = ?", String.class, doctor.getId()))
                .isEqualTo("Updated");
        assertThat(auditLogs.findByEntityNameAndEntityIdOrderByTimestampDesc("Doctor", doctor.getId()))
                .filteredOn(log -> log.getEventType() == AuditEventType.ADMIN_UPDATED_DOCTOR)
                .singleElement().satisfies(log -> assertThat(log.getPerformedByEmail()).isEqualTo(ADMIN));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedAuditInsertRollsBackCreation(boolean useDefaultActor) {
        rejectAuditInsert(creationAuditDetails(), () -> createDoctor(useDefaultActor));

        assertThat(jdbc.queryForList("select id from doctors where user_id = ?", Long.class, user.getId())).isEmpty();
        assertThat(jdbc.queryForList("select id from audit_logs where details = ?", Long.class, creationAuditDetails()))
                .isEmpty();
    }

    @Test
    void failedAuditInsertRollsBackUpdate() {
        Doctor doctor = createDoctor(false);
        String profileQuery = "select first_name, last_name, qualification, experience, specialization_id from doctors where id = ?";
        var originalProfile = jdbc.queryForMap(profileQuery, doctor.getId());

        rejectAuditInsert("Admin updated doctor profile for Doctor ID " + doctor.getId(),
                () -> doctorService.updateDoctor(doctor.getId(), updateRequest(), ADMIN));

        assertThat(jdbc.queryForMap(profileQuery, doctor.getId())).isEqualTo(originalProfile);
        assertThat(auditLogs.findByEntityNameAndEntityIdOrderByTimestampDesc("Doctor", doctor.getId()))
                .singleElement().satisfies(log -> assertThat(log.getEventType()).isEqualTo(AuditEventType.ADMIN_CREATED_DOCTOR));
    }

    private Doctor createDoctor(boolean useDefaultActor) {
        DoctorRequest request = new DoctorRequest();
        request.setUserId(user.getId());
        request.setSpecializationId(specialization.getId());
        request.setFirstName("Original");
        request.setLastName("Doctor");
        request.setQualification("MD");
        request.setExperience(3);
        return useDefaultActor ? doctorService.createDoctor(request) : doctorService.createDoctor(request, ADMIN);
    }

    private DoctorUpdateRequest updateRequest() {
        DoctorUpdateRequest request = new DoctorUpdateRequest();
        request.setSpecializationId(specialization.getId());
        request.setFirstName("Updated");
        request.setLastName("Profile");
        request.setQualification("MBBS");
        request.setExperience(8);
        return request;
    }

    private String creationAuditDetails() {
        return "Admin created doctor profile for User ID " + user.getId();
    }

    private void rejectAuditInsert(String details, Runnable operation) {
        String constraint = "test_reject_audit_" + UUID.randomUUID().toString().replace("-", "");
        // Details are a fixed test string plus a numeric fixture ID. Reject only that fixture's audit insert.
        jdbc.execute("alter table audit_logs add constraint " + constraint + " check (details <> '" + details + "')");
        try {
            assertThatThrownBy(operation::run)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .rootCause()
                    .isInstanceOfSatisfying(SQLException.class, error ->
                            assertThat(error.getMessage()).containsIgnoringCase(constraint));
        } finally {
            jdbc.execute("alter table audit_logs drop constraint " + constraint);
        }
    }
}
