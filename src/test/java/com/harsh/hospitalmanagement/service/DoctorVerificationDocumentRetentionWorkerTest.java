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
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention_worker_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationDocumentRetentionWorkerTest {

    @Autowired private DoctorRepository doctorRepository;
    @Autowired private DoctorVerificationDocumentRepository documentRepository;
    @Autowired private DoctorVerificationDocumentEventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SpecializationRepository specializationRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean private DocumentStorage documentStorage;

    private DoctorVerificationDocumentRetentionWorker worker;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM doctor_verification_document_events");
        jdbc.execute("DELETE FROM doctor_verification_documents");
        org.mockito.Mockito.reset(documentStorage);

        worker = new DoctorVerificationDocumentRetentionWorker(
                doctorRepository, documentRepository, eventRepository, transactionManager, documentStorage);

        var spec = new Specialization();
        spec.setName("spec-" + UUID.randomUUID());
        spec = specializationRepository.save(spec);

        var user = new User();
        user.setEmail("doc-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(Role.DOCTOR);
        user = userRepository.save(user);

        doctor = new Doctor();
        doctor.setUser(user);
        doctor.setSpecialization(spec);
        doctor = doctorRepository.save(doctor);
    }

    @Test
    void purgesExpiredRetiredDocument() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "a".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        doc = documentRepository.save(doc);

        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-1");
        assertThat(purged).isEqualTo(1);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isTrue();
        assertThat(updated.getPurgedAt()).isNotNull();

        verify(documentStorage).delete(DocumentKey.parse(storageKey));

        List<DoctorVerificationDocumentEvent> auditEvents = eventRepository
                .findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId());
        assertThat(auditEvents).hasSize(1);
        DoctorVerificationDocumentEvent event = auditEvents.getFirst();
        assertThat(event.getEventType()).isEqualTo(DocumentEventType.DOCUMENT_PURGED);
        assertThat(event.getActorRole()).isEqualTo(VerificationActorRole.SYSTEM);
        assertThat(event.getActorUser()).isNull();
        assertThat(event.getOutcome()).isEqualTo(DocumentEventOutcome.SUCCESS);
        assertThat(event.getDocument().getId()).isEqualTo(docId);
    }

    @Test
    void skipsDocumentWithinRetentionPeriod() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(5));
        Instant purgeDeadline = now.plus(Duration.ofDays(25));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "b".repeat(64), retiredAt.minus(Duration.ofDays(1)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-2");
        assertThat(purged).isEqualTo(0);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isFalse();
        assertThat(updated.getPurgedAt()).isNull();

        verify(documentStorage, never()).delete(any());
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }

    @Test
    void skipsActiveDocument() throws IOException {
        Instant now = Instant.now();
        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "c".repeat(64), now);
        documentRepository.save(doc);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-3");
        assertThat(purged).isEqualTo(0);

        verify(documentStorage, never()).delete(any());
    }

    @Test
    void preservesExpiredDocumentIfStillReferencedByDoctorProfile() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "d".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        // Doctor profile still references this storage key
        doctor.setIdProofDocumentPath(storageKey);
        doctorRepository.save(doctor);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-4");
        assertThat(purged).isEqualTo(0);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isFalse();

        verify(documentStorage, never()).delete(any());
    }

    @Test
    void scheduledPurgeDoesNothingWhenStorageDisabled() {
        var disabledWorker = new DoctorVerificationDocumentRetentionWorker(
                doctorRepository, documentRepository, eventRepository, transactionManager, null);
        disabledWorker.scheduledPurge();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }

    @Test
    void purgeFailsAndRollsBackOnStorageIoException() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "e".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        doc = documentRepository.save(doc);

        when(documentStorage.delete(any(DocumentKey.class))).thenThrow(new IOException("Simulated storage failure"));

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-ioe");
        assertThat(purged).isEqualTo(0);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isFalse();
        assertThat(updated.getPurgedAt()).isNull();

        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }

    @Test
    void purgeRecoversWhenPhysicalFileAlreadyDeleted() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "f".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        doc = documentRepository.save(doc);

        // Physical file returns false (already deleted from disk on prior attempt)
        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(false);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-retry");
        assertThat(purged).isEqualTo(1);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isTrue();
        assertThat(updated.getPurgedAt()).isNotNull();

        List<DoctorVerificationDocumentEvent> auditEvents = eventRepository
                .findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId());
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.getFirst().getEventType()).isEqualTo(DocumentEventType.DOCUMENT_PURGED);
    }

    @Test
    void boundedBatchFetchingLimitsPurgeCount() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        for (int i = 0; i < 5; i++) {
            UUID docId = UUID.randomUUID();
            String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
            DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "g".repeat(63) + i, retiredAt.minus(Duration.ofDays(10)));
            doc.retire(retiredAt, purgeDeadline);
            documentRepository.save(doc);
        }

        // Bounded to 2 documents
        int purged = worker.purgeExpiredDocuments(now, 2, "bounded-trace");
        assertThat(purged).isEqualTo(2);

        long unpurgedRemaining = documentRepository.findByDoctorIdOrderByCreatedAtDesc(doctor.getId()).stream()
                .filter(d -> d.isRetired() && !d.isPurged())
                .count();
        assertThat(unpurgedRemaining).isEqualTo(3);
    }

    @Test
    void concurrentPurgeExecutionsAreSafe() throws Exception {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "h".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);

        Callable<Integer> task = () -> {
            barrier.await();
            return worker.purgeExpiredDocuments(now, 10, "concurrent-trace");
        };

        Future<Integer> f1 = executor.submit(task);
        Future<Integer> f2 = executor.submit(task);

        int purged1 = f1.get();
        int purged2 = f2.get();
        executor.shutdown();

        assertThat(purged1 + purged2).isEqualTo(1);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isTrue();
    }

    @Test
    void defaultSchedulingIsDisabled() {
        // By default, scheduled purge is disabled
        assertThat(worker.isPurgeEnabled()).isFalse();
        worker.scheduledPurge();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }
}
