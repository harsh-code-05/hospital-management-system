package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentEvent;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.DocumentEventOutcome;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "HMS_DOCUMENT_TEST_DATABASE", matches = ".+")
@DataJpaTest(properties = "spring.sql.init.mode=never", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DoctorVerificationCatalogPostgresTest {

    private static final String SHA256_1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SHA256_2 = "a3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository events;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Doctor doctor1;
    private Doctor doctor2;
    private User doctorUser1;
    private User doctorUser2;
    private User adminUser;

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        String database = System.getenv("HMS_DOCUMENT_TEST_DATABASE");
        if (database == null || !database.matches("hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}")) {
            throw new IllegalArgumentException("A disposable reconciliation database name is required");
        }
        if ("hospital_management".equalsIgnoreCase(database)) {
            throw new IllegalStateException("Refusing to run tests against production hospital_management database");
        }
        String password = System.getenv("HMS_DOCUMENT_TEST_PASSWORD");
        if (password == null) {
            password = ""; // Use pgpass if password env is empty
        }
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/" + database);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        String effectivePassword = password;
        registry.add("spring.datasource.password", () -> effectivePassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeEach
    void setUp() {
        // Assert we are connected to the disposable validation target and NOT hospital_management
        String currentDb = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(currentDb).matches("hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}");
        assertThat(currentDb).isNotEqualToIgnoringCase("hospital_management");

        Long oid = jdbc.queryForObject("SELECT oid FROM pg_database WHERE datname = current_database()", Long.class);
        assertThat(oid).isNotNull().isGreaterThan(0L);

        String suffix = UUID.randomUUID().toString();
        doctorUser1 = createUser("doc1-" + suffix + "@example.invalid", Role.DOCTOR);
        doctorUser2 = createUser("doc2-" + suffix + "@example.invalid", Role.DOCTOR);
        adminUser = createUser("admin-" + suffix + "@example.invalid", Role.ADMIN);

        Specialization spec = new Specialization();
        spec.setName("Doc Catalog Spec " + suffix);
        spec = specializations.save(spec);

        doctor1 = createDoctor(doctorUser1, spec, "One");
        doctor2 = createDoctor(doctorUser2, spec, "Two");
    }

    @AfterEach
    void tearDown() {
        if (doctor1 != null && doctor2 != null) {
            jdbc.update("DELETE FROM doctor_verification_document_events WHERE doctor_id IN (?, ?)",
                    doctor1.getId(), doctor2.getId());
            jdbc.update("DELETE FROM doctor_verification_documents WHERE doctor_id IN (?, ?)",
                    doctor1.getId(), doctor2.getId());
            jdbc.update("DELETE FROM doctors WHERE id IN (?, ?)", doctor1.getId(), doctor2.getId());
        }
        if (doctorUser1 != null && doctorUser2 != null && adminUser != null) {
            jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)",
                    doctorUser1.getId(), doctorUser2.getId(), adminUser.getId());
        }
    }

    @Test
    void postgresEnforcesPartialUniqueActiveDocumentConstraint() {
        UUID doc1Id = UUID.randomUUID();
        var doc1 = new DoctorVerificationDocument(
                doc1Id, doctor1, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor1.getId() + "/id-proof/" + doc1Id,
                "image/png", 1024, SHA256_1, Instant.now()
        );
        documents.save(doc1);

        // Attempting to insert a second ACTIVE ID_PROOF for doctor1 must fail PostgreSQL partial unique index
        UUID doc2Id = UUID.randomUUID();
        var duplicateActive = new DoctorVerificationDocument(
                doc2Id, doctor1, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor1.getId() + "/id-proof/" + doc2Id,
                "image/png", 2048, SHA256_2, Instant.now()
        );
        assertThatThrownBy(() -> documents.save(duplicateActive))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_doctor_verification_documents_active");

        // Doctor 2 CAN have an active ID_PROOF (different doctor)
        UUID doc3Id = UUID.randomUUID();
        var doc2Active = new DoctorVerificationDocument(
                doc3Id, doctor2, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor2.getId() + "/id-proof/" + doc3Id,
                "image/png", 1024, SHA256_1, Instant.now()
        );
        documents.save(doc2Active);
        assertThat(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor2.getId(), VerificationDocumentKind.ID_PROOF
        )).isPresent();

        // Doctor 1 CAN have an active QUALIFICATION (different kind)
        UUID doc4Id = UUID.randomUUID();
        var doc1Qual = new DoctorVerificationDocument(
                doc4Id, doctor1, VerificationDocumentKind.QUALIFICATION,
                "verification/" + doctor1.getId() + "/qualification/" + doc4Id,
                "image/jpeg", 4096, SHA256_1, Instant.now()
        );
        documents.save(doc1Qual);
        assertThat(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor1.getId(), VerificationDocumentKind.QUALIFICATION
        )).isPresent();
    }

    @Test
    void postgresPermitsMultipleRetiredVersionsAfterRetirement() {
        UUID doc1Id = UUID.randomUUID();
        var doc1 = new DoctorVerificationDocument(
                doc1Id, doctor1, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor1.getId() + "/id-proof/" + doc1Id,
                "image/png", 1024, SHA256_1, Instant.now()
        );
        documents.save(doc1);

        // Retire doc 1
        doc1.retire(Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        documents.save(doc1);

        // Now doc 2 can be active ID_PROOF for doctor 1
        UUID doc2Id = UUID.randomUUID();
        var doc2 = new DoctorVerificationDocument(
                doc2Id, doctor1, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor1.getId() + "/id-proof/" + doc2Id,
                "image/jpeg", 2048, SHA256_2, Instant.now()
        );
        documents.save(doc2);

        // Retire doc 2 as well
        doc2.retire(Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        documents.save(doc2);

        // Multiple retired versions coexist
        var retiredDocs = documents.findByDoctorIdAndDocumentKindOrderByCreatedAtDesc(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF
        );
        assertThat(retiredDocs).hasSize(2);
        assertThat(retiredDocs).allMatch(DoctorVerificationDocument::isRetired);

        // Now doc 3 can be active ID_PROOF for doctor 1
        UUID doc3Id = UUID.randomUUID();
        var doc3 = new DoctorVerificationDocument(
                doc3Id, doctor1, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor1.getId() + "/id-proof/" + doc3Id,
                "image/png", 3072, SHA256_1, Instant.now()
        );
        documents.save(doc3);
        assertThat(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF
        )).isPresent();
    }

    @Test
    void postgresEnforcesUniqueStorageKey() {
        UUID doc1Id = UUID.randomUUID();
        String sharedStorageKey = "verification/" + doctor1.getId() + "/id-proof/" + doc1Id;
        var doc1 = new DoctorVerificationDocument(
                doc1Id, doctor1, VerificationDocumentKind.ID_PROOF,
                sharedStorageKey, "image/png", 1024, SHA256_1, Instant.now()
        );
        documents.save(doc1);

        UUID doc2Id = UUID.randomUUID();
        var doc2DuplicateKey = new DoctorVerificationDocument(
                doc2Id, doctor2, VerificationDocumentKind.ID_PROOF,
                sharedStorageKey, "image/png", 1024, SHA256_2, Instant.now()
        );
        assertThatThrownBy(() -> documents.save(doc2DuplicateKey))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_doctor_verification_documents_storage_key");
    }

    @Test
    void postgresEnforcesCheckConstraints() {
        UUID docId = UUID.randomUUID();
        // ck_doc_retired_after_created: retired_at before created_at
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO doctor_verification_documents
                (id, doctor_id, document_kind, storage_key, media_type, file_size, checksum, created_at, retired_at)
                VALUES (?, ?, 'ID_PROOF', ?, 'image/png', 1024, ?, NOW(), NOW() - INTERVAL '1 day')
                """, docId, doctor1.getId(), "verification/invalid-dates/" + docId, SHA256_1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_doc_retired_after_created");

        // ck_doc_purge_deadline: purge_deadline set when active (retired_at is null)
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO doctor_verification_documents
                (id, doctor_id, document_kind, storage_key, media_type, file_size, checksum, created_at, purge_deadline)
                VALUES (?, ?, 'ID_PROOF', ?, 'image/png', 1024, ?, NOW(), NOW() + INTERVAL '30 days')
                """, docId, doctor1.getId(), "verification/invalid-deadline/" + docId, SHA256_1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_doc_purge_deadline");

        // ck_doc_purged_after_retired: purged_at set when active (retired_at is null)
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO doctor_verification_documents
                (id, doctor_id, document_kind, storage_key, media_type, file_size, checksum, created_at, purged_at)
                VALUES (?, ?, 'ID_PROOF', ?, 'image/png', 1024, ?, NOW(), NOW())
                """, docId, doctor1.getId(), "verification/invalid-purged/" + docId, SHA256_1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_doc_purged_after_retired");

        // file_size > 5242880 violates file_size CHECK constraint
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO doctor_verification_documents
                (id, doctor_id, document_kind, storage_key, media_type, file_size, checksum, created_at)
                VALUES (?, ?, 'ID_PROOF', ?, 'image/png', 5242881, ?, NOW())
                """, UUID.randomUUID(), doctor1.getId(), "verification/invalid-size-max/" + UUID.randomUUID(), SHA256_1))
                .isInstanceOf(DataIntegrityViolationException.class);

        // file_size <= 0 violates file_size CHECK constraint
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO doctor_verification_documents
                (id, doctor_id, document_kind, storage_key, media_type, file_size, checksum, created_at)
                VALUES (?, ?, 'ID_PROOF', ?, 'image/png', 0, ?, NOW())
                """, UUID.randomUUID(), doctor1.getId(), "verification/invalid-size-zero/" + UUID.randomUUID(), SHA256_1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postgresEnforcesDocumentAuditEventActorConstraint() {
        UUID docId = UUID.randomUUID();
        var doc = new DoctorVerificationDocument(
                docId, doctor1, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor1.getId() + "/id-proof/" + docId,
                "image/png", 1024, SHA256_1, Instant.now()
        );
        documents.save(doc);

        // SYSTEM actor with actor_user_id IS NOT NULL violates ck_doctor_verification_document_events_actor
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO doctor_verification_document_events
                (event_type, doctor_id, document_id, document_kind, actor_user_id, actor_role, outcome, correlation_id)
                VALUES ('DOCUMENT_PURGED', ?, ?, 'ID_PROOF', ?, 'SYSTEM', 'SUCCESS', ?)
                """, doctor1.getId(), docId, doctorUser1.getId(), UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_doctor_verification_document_events_actor");

        // DOCTOR actor with actor_user_id IS NULL violates ck_doctor_verification_document_events_actor
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO doctor_verification_document_events
                (event_type, doctor_id, document_id, document_kind, actor_user_id, actor_role, outcome, correlation_id)
                VALUES ('DOCUMENT_ATTACHED', ?, ?, 'ID_PROOF', NULL, 'DOCTOR', 'SUCCESS', ?)
                """, doctor1.getId(), docId, UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_doctor_verification_document_events_actor");

        // Valid DOCTOR event succeeds
        var doctorEvent = new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_ATTACHED, doctor1, doc,
                VerificationDocumentKind.ID_PROOF, doctorUser1,
                VerificationActorRole.DOCTOR, DocumentEventOutcome.SUCCESS,
                UUID.randomUUID().toString(), "Initial ID proof upload", Instant.now()
        );
        events.save(doctorEvent);
        assertThat(doctorEvent.getId()).isNotNull();

        // Valid SYSTEM event succeeds (actorUser is null)
        var systemEvent = new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_PURGED, doctor1, doc,
                VerificationDocumentKind.ID_PROOF, null,
                VerificationActorRole.SYSTEM, DocumentEventOutcome.SUCCESS,
                UUID.randomUUID().toString(), "System purge worker", Instant.now()
        );
        events.save(systemEvent);
        assertThat(systemEvent.getId()).isNotNull();
    }

    @Test
    void rollbackRevertsDocumentCatalogStateAtomically() {
        UUID docId = UUID.randomUUID();
        var txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            var doc = new DoctorVerificationDocument(
                    docId, doctor1, VerificationDocumentKind.ID_PROOF,
                    "verification/" + doctor1.getId() + "/id-proof/" + docId,
                    "image/png", 1024, SHA256_1, Instant.now()
            );
            documents.save(doc);

            var event = new DoctorVerificationDocumentEvent(
                    DocumentEventType.DOCUMENT_ATTACHED, doctor1, doc,
                    VerificationDocumentKind.ID_PROOF, doctorUser1,
                    VerificationActorRole.DOCTOR, DocumentEventOutcome.SUCCESS,
                    UUID.randomUUID().toString(), "Atomic test", Instant.now()
            );
            events.save(event);

            throw new RuntimeException("Simulated transaction rollback");
        })).hasMessage("Simulated transaction rollback");

        assertThat(documents.findById(docId)).isEmpty();
        assertThat(events.findByDocumentIdOrderByOccurredAtDescIdDesc(docId)).isEmpty();
    }

    @Test
    void postgresPurgeScanIndexExistsAndSupportsRetentionQuery() {
        // Confirm index exists in PostgreSQL catalog with correct partial predicate
        String indexDef = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'doctor_verification_documents'
                  AND indexname = 'ix_doctor_verification_documents_purge_scan'
                """, String.class);
        assertThat(indexDef).isNotNull();
        assertThat(indexDef).contains("purge_deadline");
        assertThat(indexDef).containsIgnoringCase("retired_at IS NOT NULL");
        assertThat(indexDef).containsIgnoringCase("purged_at IS NULL");

        Instant now = Instant.now();
        Instant pastDeadline = now.minus(5, ChronoUnit.DAYS);
        Instant futureDeadline = now.plus(25, ChronoUnit.DAYS);

        // Doc 1: Active (not retired) -> should NOT be returned
        UUID doc1Id = UUID.randomUUID();
        var doc1 = new DoctorVerificationDocument(
                doc1Id, doctor1, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor1.getId() + "/id-proof/" + doc1Id,
                "image/png", 1024, SHA256_1, now.minus(60, ChronoUnit.DAYS)
        );
        documents.save(doc1);

        // Doc 2: Retired, purge_deadline in past -> MUST be returned by purge query
        UUID doc2Id = UUID.randomUUID();
        var doc2 = new DoctorVerificationDocument(
                doc2Id, doctor2, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor2.getId() + "/id-proof/" + doc2Id,
                "image/png", 1024, SHA256_2, now.minus(60, ChronoUnit.DAYS)
        );
        doc2.retire(now.minus(35, ChronoUnit.DAYS), pastDeadline);
        documents.save(doc2);

        // Doc 3: Retired, purge_deadline in future -> should NOT be returned
        UUID doc3Id = UUID.randomUUID();
        var doc3 = new DoctorVerificationDocument(
                doc3Id, doctor2, VerificationDocumentKind.QUALIFICATION,
                "verification/" + doctor2.getId() + "/qualification/" + doc3Id,
                "image/jpeg", 2048, SHA256_1, now.minus(10, ChronoUnit.DAYS)
        );
        doc3.retire(now.minus(5, ChronoUnit.DAYS), futureDeadline);
        documents.save(doc3);

        // Doc 4: Retired and already purged -> should NOT be returned
        UUID doc4Id = UUID.randomUUID();
        var doc4 = new DoctorVerificationDocument(
                doc4Id, doctor1, VerificationDocumentKind.QUALIFICATION,
                "verification/" + doctor1.getId() + "/qualification/" + doc4Id,
                "image/jpeg", 4096, SHA256_2, now.minus(90, ChronoUnit.DAYS)
        );
        doc4.retire(now.minus(60, ChronoUnit.DAYS), pastDeadline);
        doc4.markPurged(now.minus(1, ChronoUnit.DAYS));
        documents.save(doc4);

        // Execute repository purge query
        var candidates = documents.findByRetiredAtIsNotNullAndPurgedAtIsNullAndPurgeDeadlineBefore(now);
        assertThat(candidates).extracting(DoctorVerificationDocument::getId)
                .contains(doc2Id)
                .doesNotContain(doc1Id, doc3Id, doc4Id);
    }

    private User createUser(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        return users.save(u);
    }

    private Doctor createDoctor(User user, Specialization spec, String last) {
        Doctor d = new Doctor();
        d.setUser(user);
        d.setSpecialization(spec);
        d.setFirstName("Dr");
        d.setLastName(last);
        return doctors.save(d);
    }
}
