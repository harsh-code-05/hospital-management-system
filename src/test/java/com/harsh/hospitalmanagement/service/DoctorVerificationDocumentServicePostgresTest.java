package com.harsh.hospitalmanagement.service;

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
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.MasterKeyProvider;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "HMS_DOCUMENT_TEST_DATABASE", matches = ".+")
@DataJpaTest(properties = "spring.sql.init.mode=never", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DoctorManagementAccess.class, DoctorVerificationDocumentService.class, DoctorVerificationDocumentServicePostgresTest.StorageTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DoctorVerificationDocumentServicePostgresTest {

    @Autowired private DoctorRepository doctors;
    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository events;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorManagementAccess access;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Autowired private DoctorVerificationDocumentService service;
    @Autowired private DocumentStorage storage;

    private VerificationImageValidator validator;
    private Path protectedRoot;

    @TestConfiguration
    static class StorageTestConfig {
        static Path testStorageRoot;
        static MasterKeyProvider testMasterKeyProvider = new EphemeralMasterKeyProvider(1);

        @Bean
        DocumentStorage documentStorage() throws IOException {
            if (testStorageRoot == null) {
                Path base = Files.createTempDirectory("hms-test-docs-");
                testStorageRoot = protectedDirectory(base.resolve("documents"));
            }
            return DocumentStorage.createFilesystemStorage(testStorageRoot, testMasterKeyProvider, UUID::randomUUID);
        }
    }

    private Doctor doctor1;
    private Doctor doctor2;
    private User doctorUser1;
    private User doctorUser2;
    private User adminUser;
    private User patientUser;
    private ValidatedImage pngImage;
    private ValidatedImage jpegImage;

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
        String effectivePassword = (password != null) ? password : "";
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/" + database);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> effectivePassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeEach
    void setUp() throws IOException {
        String currentDb = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(currentDb).matches("hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}");
        assertThat(currentDb).isNotEqualToIgnoringCase("hospital_management");

        Long oid = jdbc.queryForObject("SELECT oid FROM pg_database WHERE datname = current_database()", Long.class);
        assertThat(oid).isNotNull().isGreaterThan(0L);

        assertThat(AopUtils.isAopProxy(service)).isTrue();
        protectedRoot = StorageTestConfig.testStorageRoot;

        validator = new VerificationImageValidator();
        pngImage = validator.validate("image/png", new ByteArrayInputStream(createImageBytes("png")));
        jpegImage = validator.validate("image/jpeg", new ByteArrayInputStream(createImageBytes("jpeg")));

        String suffix = UUID.randomUUID().toString();
        doctorUser1 = createUser("doc1-svc-" + suffix + "@example.invalid", Role.DOCTOR);
        doctorUser2 = createUser("doc2-svc-" + suffix + "@example.invalid", Role.DOCTOR);
        adminUser = createUser("admin-svc-" + suffix + "@example.invalid", Role.ADMIN);
        patientUser = createUser("patient-svc-" + suffix + "@example.invalid", Role.PATIENT);

        Specialization spec = new Specialization();
        spec.setName("Doc Svc Spec " + suffix);
        spec = specializations.save(spec);

        doctor1 = createDoctor(doctorUser1, spec, "Alpha");
        doctor2 = createDoctor(doctorUser2, spec, "Beta");
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
            jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?)",
                    doctorUser1.getId(), doctorUser2.getId(), adminUser.getId(),
                    patientUser != null ? patientUser.getId() : -1L);
        }
    }

    @Test
    void test01_successfulFirstAttachment() throws IOException {
        // Doctor in UNVERIFIED state attaches ID_PROOF
        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage,
                auth(doctorUser1), "corr-attach-1"
        );

        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.isActive()).isTrue();
        assertThat(doc.getDocumentKind()).isEqualTo(VerificationDocumentKind.ID_PROOF);
        assertThat(doc.getMediaType()).isEqualTo("image/png");
        assertThat(doc.getFileSize()).isEqualTo(pngImage.size());
        assertThat(doc.getChecksum()).isEqualTo(pngImage.sha256Hex());
        assertThat(doc.getStorageKey()).startsWith("verification/" + doctor1.getId() + "/id-proof/");

        // Verify Doctor entity was updated with storage key
        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isEqualTo(doc.getStorageKey());
        assertThat(reloaded.getQualificationDocumentPath()).isNull();

        // Verify encrypted file was written to disk and is readable
        DocumentKey key = DocumentKey.parse(doc.getStorageKey());
        assertThat(storage.exists(key)).isTrue();
        var readResult = storage.read(key);
        assertThat(readResult.contentType()).isEqualTo("image/png");
        assertThat(readResult.bytes()).isEqualTo(pngImage.copyBytes());

        // Verify audit event
        var eventList = events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId());
        assertThat(eventList).hasSize(1);
        var event = eventList.get(0);
        assertThat(event.getEventType()).isEqualTo(DocumentEventType.DOCUMENT_ATTACHED);
        assertThat(event.getActorRole()).isEqualTo(VerificationActorRole.DOCTOR);
        assertThat(event.getActorUser().getId()).isEqualTo(doctorUser1.getId());
        assertThat(event.getOutcome()).isEqualTo(DocumentEventOutcome.SUCCESS);
        assertThat(event.getCorrelationId()).isEqualTo("corr-attach-1");
    }

    @Test
    void test02_replacementOfActiveDocument() throws IOException {
        // First attachment
        var firstDoc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage,
                auth(doctorUser1), "corr-replace-1"
        );

        // Replacement with new image
        var secondDoc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, jpegImage,
                auth(doctorUser1), "corr-replace-2"
        );

        assertThat(secondDoc.getId()).isNotEqualTo(firstDoc.getId());
        assertThat(secondDoc.isActive()).isTrue();
        assertThat(secondDoc.getMediaType()).isEqualTo("image/jpeg");

        // First doc must be retired
        var reloadedFirst = documents.findById(firstDoc.getId()).orElseThrow();
        assertThat(reloadedFirst.isRetired()).isTrue();
        assertThat(reloadedFirst.getRetiredAt()).isNotNull();
        assertThat(reloadedFirst.getPurgeDeadline()).isNotNull();

        // Doctor entity points to the replacement key
        Doctor reloadedDoctor = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloadedDoctor.getIdProofDocumentPath()).isEqualTo(secondDoc.getStorageKey());

        // Both encrypted files exist on disk
        assertThat(storage.exists(DocumentKey.parse(firstDoc.getStorageKey()))).isTrue();
        assertThat(storage.exists(DocumentKey.parse(secondDoc.getStorageKey()))).isTrue();

        // Audit events verify DOCUMENT_ATTACHED followed by DOCUMENT_REPLACED
        var eventList = events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId());
        assertThat(eventList).hasSize(2);
        assertThat(eventList.get(0).getEventType()).isEqualTo(DocumentEventType.DOCUMENT_REPLACED);
        assertThat(eventList.get(1).getEventType()).isEqualTo(DocumentEventType.DOCUMENT_ATTACHED);
    }

    @Test
    void test03_multipleHistoricalRetiredVersions() {
        var v1 = service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-1");
        var v2 = service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, jpegImage, auth(doctorUser1), "c-2");
        var v3 = service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-3");

        var history = service.getDocumentHistory(doctor1.getId(), VerificationDocumentKind.ID_PROOF);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).getId()).isEqualTo(v3.getId());
        assertThat(history.get(0).isActive()).isTrue();
        assertThat(history.get(1).getId()).isEqualTo(v2.getId());
        assertThat(history.get(1).isRetired()).isTrue();
        assertThat(history.get(2).getId()).isEqualTo(v1.getId());
        assertThat(history.get(2).isRetired()).isTrue();
    }

    @Test
    void test04_oneActiveVersionPerDoctorAndKind() {
        service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-1");
        var active = service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF);
        assertThat(active).isPresent();

        // Replace and re-check active
        var v2 = service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, jpegImage, auth(doctorUser1), "c-2");
        var currentActive = service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF);
        assertThat(currentActive).isPresent();
        assertThat(currentActive.get().getId()).isEqualTo(v2.getId());
    }

    @Test
    void test05_idProofAndQualificationCoexistence() {
        var idDoc = service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-id");
        var qualDoc = service.attachDocument(doctor1.getId(), VerificationDocumentKind.QUALIFICATION, jpegImage, auth(doctorUser1), "c-qual");

        assertThat(idDoc.isActive()).isTrue();
        assertThat(qualDoc.isActive()).isTrue();

        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isEqualTo(idDoc.getStorageKey());
        assertThat(reloaded.getQualificationDocumentPath()).isEqualTo(qualDoc.getStorageKey());

        var activeId = service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF);
        var activeQual = service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.QUALIFICATION);
        assertThat(activeId).isPresent();
        assertThat(activeQual).isPresent();
        assertThat(activeId.get().getId()).isEqualTo(idDoc.getId());
        assertThat(activeQual.get().getId()).isEqualTo(qualDoc.getId());
    }

    @Test
    void test06_databaseRollbackAfterInjectedFailure() throws IOException {
        // Initial state: doctor has no documents
        var txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-fail");
            throw new RuntimeException("Simulated database failure during attachment");
        })).hasMessage("Simulated database failure during attachment");

        // Database rollback confirmed: no active catalog row, no event row, doctor path is null
        assertThat(service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF)).isEmpty();
        assertThat(events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId())).isEmpty();
        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isNull();
    }

    @Test
    void test07_filesystemFailureBeforeDatabasePublication() throws IOException {
        // Storage mock/failure simulator
        DocumentStorage failingStorage = new DocumentStorage() {
            @Override
            public DocumentKey store(long doctorId, DoctorVerificationService.DocumentKind kind, ValidatedImage image) throws IOException {
                throw new IOException("Simulated storage write failure: disk full");
            }
            @Override public ReadDocument read(DocumentKey key) { return null; }
            @Override public void verifyIntegrity(DocumentKey key) {}
            @Override public boolean exists(DocumentKey key) { return false; }
            @Override public boolean delete(DocumentKey key) { return false; }
        };

        var txTemplate = new TransactionTemplate(transactionManager);
        var failingService = new DoctorVerificationDocumentService(doctors, documents, events, access, users, failingStorage);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            return failingService.attachDocument(
                    doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-storage-fail"
            );
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to write encrypted document");

        // Verify zero database publication occurred
        assertThat(documents.findByDoctorIdAndDocumentKindOrderByCreatedAtDesc(doctor1.getId(), VerificationDocumentKind.ID_PROOF)).isEmpty();
        assertThat(events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId())).isEmpty();
        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isNull();
    }

    @Test
    void test08_existingAttachmentPreservationOnReplacementFailure() {
        // Successfully attach initial document
        var initialDoc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-initial"
        );

        // Attempt replacement inside transaction that fails
        var txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, jpegImage, auth(doctorUser1), "c-replace-fail");
            throw new RuntimeException("Simulated mid-transaction crash");
        })).hasMessage("Simulated mid-transaction crash");

        // Previous document must remain completely ACTIVE and unchanged
        var currentActive = service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF).orElseThrow();
        assertThat(currentActive.getId()).isEqualTo(initialDoc.getId());
        assertThat(currentActive.isActive()).isTrue();
        assertThat(currentActive.isRetired()).isFalse();

        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isEqualTo(initialDoc.getStorageKey());
    }

    @Test
    void test09_doctorOwnershipAndVerificationStateRestrictions() {
        // Non-owner doctor cannot attach documents to doctor1
        assertThatThrownBy(() -> service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser2), "c-tamper"
        )).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not allowed to manage this doctor");

        // Transition doctor1 to SUBMITTED
        doctor1.setVerificationStatus(VerificationStatus.SUBMITTED);
        doctor1 = doctors.saveAndFlush(doctor1);

        assertThatThrownBy(() -> service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-submitted"
        )).isInstanceOf(ConflictException.class)
                .hasMessageContaining("before submission or after rejection");

        // Transition doctor1 to UNDER_REVIEW
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctor1 = doctors.saveAndFlush(doctor1);

        assertThatThrownBy(() -> service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-under-review"
        )).isInstanceOf(ConflictException.class)
                .hasMessageContaining("before submission or after rejection");

        // Transition doctor1 to REJECTED (draft state restored) -> attachment succeeds
        doctor1.setVerificationStatus(VerificationStatus.REJECTED);
        doctor1 = doctors.saveAndFlush(doctor1);

        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-rejected-attach"
        );
        assertThat(doc.isActive()).isTrue();
    }

    @Test
    void test10_auditEventConsistencyAndAdminAccess() throws IOException {
        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "corr-101"
        );

        // 1. Doctor attempting to read own document must be denied with ForbiddenException
        assertThatThrownBy(() -> service.readDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, auth(doctorUser1), "corr-102"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only administrators may access raw verification evidence");

        // 2. Patient attempting to read document must be denied with ForbiddenException
        assertThatThrownBy(() -> service.readDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, auth(patientUser), "corr-103"))
                .isInstanceOf(ForbiddenException.class);

        // 3. Admin attempting to read document when doctor is in UNVERIFIED status must be denied with ConflictException
        assertThatThrownBy(() -> service.readDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, auth(adminUser), "corr-104"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("when the doctor is under review");

        // Transition doctor to SUBMITTED -> Admin read still denied
        doctor1.setVerificationStatus(VerificationStatus.SUBMITTED);
        doctor1 = doctors.saveAndFlush(doctor1);
        assertThatThrownBy(() -> service.readDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, auth(adminUser), "corr-105"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("when the doctor is under review");

        // Transition doctor to UNDER_REVIEW -> Admin read succeeds!
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctor1 = doctors.saveAndFlush(doctor1);

        var readByAdmin = service.readDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, auth(adminUser), "corr-admin-106");
        assertThat(readByAdmin.bytes()).isEqualTo(pngImage.copyBytes());

        // Verify ADMIN_ACCESS_GRANTED event was committed to database before returning
        var auditList = events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId());
        assertThat(auditList).hasSize(2); // 1 attach + 1 admin read

        var adminEvent = auditList.get(0);
        assertThat(adminEvent.getEventType()).isEqualTo(DocumentEventType.ADMIN_ACCESS_GRANTED);
        assertThat(adminEvent.getActorRole()).isEqualTo(VerificationActorRole.ADMIN);
        assertThat(adminEvent.getActorUser().getId()).isEqualTo(adminUser.getId());
        assertThat(adminEvent.getOutcome()).isEqualTo(DocumentEventOutcome.SUCCESS);
        assertThat(adminEvent.getCorrelationId()).isEqualTo("corr-admin-106");
        assertThat(adminEvent.getDetails()).doesNotContain("C:\\");
        assertThat(adminEvent.getDetails()).doesNotContain("/var/");
    }

    @Test
    void test11_storageKeyAndChecksumCorrectness() {
        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-checksum"
        );

        // Storage key format
        assertThat(doc.getStorageKey()).matches("^verification/" + doctor1.getId() + "/id-proof/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$");

        // SHA-256 checksum format
        assertThat(doc.getChecksum()).matches("^[0-9a-f]{64}$");
        assertThat(doc.getChecksum()).isEqualTo(pngImage.sha256Hex());

        // DocumentKey parse compatibility
        DocumentKey key = DocumentKey.parse(doc.getStorageKey());
        assertThat(key.doctorId()).isEqualTo(doctor1.getId());
        assertThat(key.kind()).isEqualTo(DoctorVerificationService.DocumentKind.ID_PROOF);
        assertThat(key.version()).isEqualTo(doc.getId());
    }

    @Test
    void test12_encryptedFileIntegrityAfterRetrievalAndTampering() throws IOException {
        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.QUALIFICATION, jpegImage, auth(doctorUser1), "c-integrity"
        );

        // Transition doctor to UNDER_REVIEW so Admin can retrieve document
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctor1 = doctors.saveAndFlush(doctor1);

        // Valid read and decrypted bytes by Admin
        var readResult = service.readDocument(doctor1.getId(), VerificationDocumentKind.QUALIFICATION, auth(adminUser), "c-read");
        assertThat(readResult.contentType()).isEqualTo("image/jpeg");
        assertThat(readResult.bytes()).isEqualTo(jpegImage.copyBytes());

        // Tamper with the encrypted file on disk
        DocumentKey key = DocumentKey.parse(doc.getStorageKey());
        Path filePath = protectedRoot.resolve("verification").resolve(Long.toString(doctor1.getId()))
                .resolve("qualification").resolve(key.version().toString());
        assertThat(Files.exists(filePath)).isTrue();

        byte[] fileBytes = Files.readAllBytes(filePath);
        fileBytes[fileBytes.length - 1] ^= 0xFF; // Corrupt AES-GCM authentication tag
        Files.write(filePath, fileBytes);

        // Reading tampered file must fail closed
        assertThatThrownBy(() -> service.readDocument(doctor1.getId(), VerificationDocumentKind.QUALIFICATION, auth(adminUser), "c-tampered"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read or authenticate verification document from storage");
    }

    @Test
    void test13_detachmentRetiresDocumentAndClearsDoctorPath() {
        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-detach-setup"
        );

        var detached = service.detachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, auth(doctorUser1), "c-detach"
        );

        assertThat(detached.getId()).isEqualTo(doc.getId());
        assertThat(detached.isRetired()).isTrue();
        assertThat(detached.getPurgeDeadline()).isNotNull();

        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isNull();

        var auditList = events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId());
        assertThat(auditList.get(0).getEventType()).isEqualTo(DocumentEventType.DOCUMENT_DETACHED);
        assertThat(auditList.get(0).getOutcome()).isEqualTo(DocumentEventOutcome.SUCCESS);
    }

    @Test
    void test14_orphanReconciliationPreservesReferencedAndRecentFiles() throws IOException {
        // 1. Referenced file -> REFERENCED decision
        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-recon"
        );
        DocumentKey activeKey = DocumentKey.parse(doc.getStorageKey());
        var decision1 = service.reconcileOrphanCandidate(activeKey, Instant.now().minus(2, ChronoUnit.HOURS), Duration.ofHours(1));
        assertThat(decision1).isEqualTo(DoctorVerificationDocumentOrphanReconciliation.ReconciliationDecision.REFERENCED);
        assertThat(storage.exists(activeKey)).isTrue();

        // 2. Unreferenced file within grace period -> WITHIN_GRACE decision (preserved)
        UUID orphanUuid = UUID.randomUUID();
        DocumentKey orphanKey = storage.store(doctor1.getId(), DoctorVerificationService.DocumentKind.QUALIFICATION, jpegImage);
        var decision2 = service.reconcileOrphanCandidate(orphanKey, Instant.now(), Duration.ofHours(1));
        assertThat(decision2).isEqualTo(DoctorVerificationDocumentOrphanReconciliation.ReconciliationDecision.WITHIN_GRACE);
        assertThat(storage.exists(orphanKey)).isTrue();

        // 3. Unreferenced file outside grace period -> ORPHAN_DELETED decision (safely cleaned)
        var decision3 = service.reconcileOrphanCandidate(orphanKey, Instant.now().minus(2, ChronoUnit.HOURS), Duration.ofHours(1));
        assertThat(decision3).isEqualTo(DoctorVerificationDocumentOrphanReconciliation.ReconciliationDecision.ORPHAN_DELETED);
        assertThat(storage.exists(orphanKey)).isFalse();
    }

    @Test
    void test15_orphanReconciliationInputValidationAndOverflow() throws IOException {
        DocumentKey sampleKey = DocumentKey.parse("verification/" + doctor1.getId() + "/id-proof/" + UUID.randomUUID());

        // Null key rejected
        assertThatThrownBy(() -> service.reconcileOrphanCandidate(null, Instant.now(), Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("DocumentKey must not be null");

        // Null candidate creation timestamp rejected
        assertThatThrownBy(() -> service.reconcileOrphanCandidate(sampleKey, null, Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Candidate creation timestamp must not be null");

        // Null grace period rejected
        assertThatThrownBy(() -> service.reconcileOrphanCandidate(sampleKey, Instant.now(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GracePeriod must not be null");

        // Negative grace period rejected
        assertThatThrownBy(() -> service.reconcileOrphanCandidate(sampleKey, Instant.now(), Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Grace period must be positive");

        // Zero grace period rejected
        assertThatThrownBy(() -> service.reconcileOrphanCandidate(sampleKey, Instant.now(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Grace period must be positive");

        // Arithmetic overflow: Duration.ofSeconds(Long.MAX_VALUE) handled safely as within grace
        DocumentKey orphanKey = storage.store(doctor1.getId(), DoctorVerificationService.DocumentKind.ID_PROOF, pngImage);
        var overflowDecision = service.reconcileOrphanCandidate(orphanKey, Instant.now(), Duration.ofSeconds(Long.MAX_VALUE));
        assertThat(overflowDecision).isEqualTo(DoctorVerificationDocumentOrphanReconciliation.ReconciliationDecision.WITHIN_GRACE);
        assertThat(storage.exists(orphanKey)).isTrue();
    }

    @Test
    void test16_correlationIdSanitizationBoundaries() {
        // 1. Boundary: exactly 64 characters preserved intact
        String exact64 = "C".repeat(64);
        assertThat(DoctorVerificationDocumentService.sanitizeCorrelationId(exact64))
                .isEqualTo(exact64)
                .hasSize(64);

        // 2. Boundary: 65 characters compacted deterministically to exactly 64 characters
        String input65A = "D".repeat(64) + "1";
        String input65B = "D".repeat(64) + "2";
        String sanitized65A = DoctorVerificationDocumentService.sanitizeCorrelationId(input65A);
        String sanitized65B = DoctorVerificationDocumentService.sanitizeCorrelationId(input65B);

        assertThat(sanitized65A).hasSize(64);
        assertThat(sanitized65B).hasSize(64);
        // Collision resistance: different 65-char inputs produce different sanitized 64-char IDs
        assertThat(sanitized65A).isNotEqualTo(sanitized65B);

        // 3. Boundary: 100 characters compacted deterministically to exactly 64 characters
        String input100 = "E".repeat(100);
        String sanitized100 = DoctorVerificationDocumentService.sanitizeCorrelationId(input100);
        assertThat(sanitized100).hasSize(64);

        // 4. Boundary: null and blank fallback to valid 36-char UUID
        assertThat(DoctorVerificationDocumentService.sanitizeCorrelationId(null)).hasSize(36);
        assertThat(DoctorVerificationDocumentService.sanitizeCorrelationId("   ")).hasSize(36);

        // 5. Database persistence check with 65-char correlation ID
        var doc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), input65A
        );
        var auditEvents = events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId());
        assertThat(auditEvents).isNotEmpty();
        assertThat(auditEvents.get(0).getCorrelationId()).isEqualTo(sanitized65A).hasSize(64);
    }

    @Test
    void test17_concurrentAttachmentContentionOnDoctorLock() throws Exception {
        int threads = 2;
        var executor = Executors.newFixedThreadPool(threads);
        var barrier = new CyclicBarrier(threads);
        var futures = new ArrayList<Future<DoctorVerificationDocument>>();

        // Thread 1 attaches ID_PROOF png, Thread 2 attaches ID_PROOF jpeg simultaneously
        futures.add(executor.submit(() -> {
            barrier.await();
            return service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "corr-conc-1");
        }));
        futures.add(executor.submit(() -> {
            barrier.await();
            return service.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, jpegImage, auth(doctorUser1), "corr-conc-2");
        }));

        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly one active document remains in PostgreSQL
        var activeOpt = service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF);
        assertThat(activeOpt).isPresent();

        // Total documents in history is 2 (1 active, 1 retired)
        var history = service.getDocumentHistory(doctor1.getId(), VerificationDocumentKind.ID_PROOF);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).isActive()).isTrue();
        assertThat(history.get(1).isRetired()).isTrue();

        // Doctor entity reference matches the active document
        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isEqualTo(activeOpt.get().getStorageKey());

        // Both audit events are recorded
        var docEvents = events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor1.getId());
        assertThat(docEvents).hasSize(2);
    }

    @Test
    void test18_auditPersistenceFailureRollback() {
        // Create a mock event repository that throws an exception on save
        var failingEvents = Mockito.mock(DoctorVerificationDocumentEventRepository.class);
        Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated audit table failure"))
                .when(failingEvents).save(any());

        var failingService = new DoctorVerificationDocumentService(
                doctors, documents, failingEvents, access, users, transactionManager, storage
        );
        var txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        // Initial attachment fails during audit event save -> database rolls back cleanly
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            return failingService.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-audit-fail");
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // Verify zero catalog records published, doctor path is null
        assertThat(service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF)).isEmpty();
        Doctor reloaded = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloaded.getIdProofDocumentPath()).isNull();

        // Now attach initial document with real service successfully
        var initialDoc = service.attachDocument(
                doctor1.getId(), VerificationDocumentKind.ID_PROOF, pngImage, auth(doctorUser1), "c-initial"
        );
        assertThat(initialDoc.isActive()).isTrue();

        // Now attempt replacement with failing service -> audit failure causes rollback
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            return failingService.attachDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF, jpegImage, auth(doctorUser1), "c-replace-fail");
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // Previously valid document remains completely ACTIVE and doctor reference intact
        var currentActive = service.getActiveDocument(doctor1.getId(), VerificationDocumentKind.ID_PROOF).orElseThrow();
        assertThat(currentActive.getId()).isEqualTo(initialDoc.getId());
        assertThat(currentActive.isActive()).isTrue();
        assertThat(currentActive.isRetired()).isFalse();

        Doctor reloadedAfterFail = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(reloadedAfterFail.getIdProofDocumentPath()).isEqualTo(initialDoc.getStorageKey());
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

    private Authentication auth(User user) {
        var account = new LocalAccount(user.getId(), user.getEmail());
        var principal = new LocalAccount.PasswordPrincipal(account, "hash", user.getRole());
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static byte[] createImageBytes(String format) throws IOException {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        image.setRGB(4, 4, Color.BLUE.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            assertThat(ImageIO.write(image, format, stream)).isTrue();
        }
        return bytes.toByteArray();
    }

    static final class EphemeralMasterKeyProvider implements MasterKeyProvider {
        private final SecretKey masterKey;
        private final int version;

        EphemeralMasterKeyProvider(int version) {
            this.version = version;
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            this.masterKey = new SecretKeySpec(keyBytes, "AES");
        }

        @Override
        public int activeVersion() {
            return version;
        }

        @Override
        public byte[] wrapDataKey(int version, byte[] dataKey) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AESWrap");
            cipher.init(Cipher.WRAP_MODE, masterKey);
            return cipher.wrap(new SecretKeySpec(dataKey, "AES"));
        }

        @Override
        public byte[] unwrapDataKey(int version, byte[] wrappedKey) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AESWrap");
            cipher.init(Cipher.UNWRAP_MODE, masterKey);
            return cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY).getEncoded();
        }
    }

    private static Path protectedDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new IOException("Failed to open current process token");
        }
        try {
            var sid = new WinNT.PSID(Advapi32Util.getTokenAccount(token.getValue()).sid);
            var acl = new WinNT.ACL(1024);
            if (!Advapi32.INSTANCE.InitializeAcl(acl, 1024, WinNT.ACL_REVISION)) {
                throw new IOException("Failed to initialize ACL");
            }
            if (!Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION,
                    WinNT.OBJECT_INHERIT_ACE | WinNT.CONTAINER_INHERIT_ACE, WinNT.FILE_ALL_ACCESS, sid)) {
                throw new IOException("Failed to add ACE");
            }
            if (Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(), SE_FILE_OBJECT,
                    WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                    null, null, acl.getPointer(), null) != 0) {
                throw new IOException("Failed to set security info");
            }
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
        return path;
    }
}
