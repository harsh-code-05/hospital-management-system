package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentEvent;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentPolicy;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.DocumentEventOutcome;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class DoctorVerificationCatalogRepositoryTest {

    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository events;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;

    private Doctor doctor;
    private User doctorUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        doctorUser = new User();
        doctorUser.setEmail("doc-cat-" + suffix + "@example.invalid");
        doctorUser.setRole(Role.DOCTOR);
        doctorUser = users.save(doctorUser);

        adminUser = new User();
        adminUser.setEmail("admin-cat-" + suffix + "@example.invalid");
        adminUser.setRole(Role.ADMIN);
        adminUser = users.save(adminUser);

        Specialization spec = new Specialization();
        spec.setName("Catalog Spec " + suffix);
        spec = specializations.save(spec);

        doctor = new Doctor();
        doctor.setUser(doctorUser);
        doctor.setSpecialization(spec);
        doctor.setFirstName("Document");
        doctor.setLastName("Doctor");
        doctor = doctors.save(doctor);
    }

    @Test
    void persistsAndRetrievesActiveDocument() {
        UUID docId = UUID.randomUUID();
        String storageKey = "verification/" + doctor.getId() + "/id-proof/" + docId;
        String checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        var document = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF,
                storageKey, "image/png", 1024, checksum, Instant.now()
        );
        documents.save(document);

        var retrieved = documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), VerificationDocumentKind.ID_PROOF
        );
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getId()).isEqualTo(docId);
        assertThat(retrieved.get().isActive()).isTrue();
        assertThat(retrieved.get().isRetired()).isFalse();
        assertThat(retrieved.get().getStorageKey()).isEqualTo(storageKey);
        assertThat(retrieved.get().getMediaType()).isEqualTo("image/png");
        assertThat(retrieved.get().getFileSize()).isEqualTo(1024);
        assertThat(retrieved.get().getChecksum()).isEqualTo(checksum);

        assertThat(documents.findByStorageKey(storageKey)).isPresent();
        assertThat(documents.existsByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), VerificationDocumentKind.ID_PROOF
        )).isTrue();
        assertThat(documents.existsByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), VerificationDocumentKind.QUALIFICATION
        )).isFalse();
    }

    @Test
    void supportsRetirementAndLifecycleTransitions() {
        UUID firstId = UUID.randomUUID();
        var firstDoc = new DoctorVerificationDocument(
                firstId, doctor, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor.getId() + "/id-proof/" + firstId,
                "image/png", 2048, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Instant.now()
        );
        documents.save(firstDoc);

        Instant retireTime = Instant.now().plusSeconds(60);
        Instant purgeDeadline = Instant.now().plus(30, ChronoUnit.DAYS);
        firstDoc.retire(retireTime, purgeDeadline);
        documents.save(firstDoc);

        assertThat(firstDoc.isActive()).isFalse();
        assertThat(firstDoc.isRetired()).isTrue();
        assertThat(firstDoc.isPurged()).isFalse();
        assertThat(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), VerificationDocumentKind.ID_PROOF
        )).isEmpty();

        // New active document
        UUID secondId = UUID.randomUUID();
        var secondDoc = new DoctorVerificationDocument(
                secondId, doctor, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor.getId() + "/id-proof/" + secondId,
                "image/jpeg", 4096, "f3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Instant.now()
        );
        documents.save(secondDoc);

        var active = documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), VerificationDocumentKind.ID_PROOF
        );
        assertThat(active).isPresent();
        assertThat(active.get().getId()).isEqualTo(secondId);

        var allDocs = documents.findByDoctorIdAndDocumentKindOrderByCreatedAtDesc(
                doctor.getId(), VerificationDocumentKind.ID_PROOF
        );
        assertThat(allDocs).hasSize(2);

        // Mark first purged
        Instant purgeTime = Instant.now().plus(31, ChronoUnit.DAYS);
        firstDoc.markPurged(purgeTime);
        documents.save(firstDoc);
        assertThat(firstDoc.isPurged()).isTrue();
    }

    @Test
    void recordsAndQueriesDocumentAuditEvents() {
        UUID docId = UUID.randomUUID();
        var document = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor.getId() + "/id-proof/" + docId,
                "image/png", 1024, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Instant.now()
        );
        documents.save(document);

        String correlationId = UUID.randomUUID().toString();
        var attachEvent = new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_ATTACHED, doctor, document,
                VerificationDocumentKind.ID_PROOF, doctorUser,
                VerificationActorRole.DOCTOR, DocumentEventOutcome.SUCCESS,
                correlationId, "Initial ID proof upload", Instant.now()
        );
        events.save(attachEvent);

        var adminViewEvent = new DoctorVerificationDocumentEvent(
                DocumentEventType.ADMIN_ACCESS_GRANTED, doctor, document,
                VerificationDocumentKind.ID_PROOF, adminUser,
                VerificationActorRole.ADMIN, DocumentEventOutcome.SUCCESS,
                correlationId, "Admin viewed credential", Instant.now().plusSeconds(10)
        );
        events.save(adminViewEvent);

        var doctorEvents = events.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId());
        assertThat(doctorEvents).hasSize(2);
        assertThat(doctorEvents.get(0).getEventType()).isEqualTo(DocumentEventType.ADMIN_ACCESS_GRANTED);
        assertThat(doctorEvents.get(1).getEventType()).isEqualTo(DocumentEventType.DOCUMENT_ATTACHED);

        var docEvents = events.findByDocumentIdOrderByOccurredAtDescIdDesc(docId);
        assertThat(docEvents).hasSize(2);

        var correlationEvents = events.findByCorrelationIdOrderByOccurredAtDescIdDesc(correlationId);
        assertThat(correlationEvents).hasSize(2);
    }

    @Test
    void rejectsInvalidActorCombinationsInEvent() {
        UUID docId = UUID.randomUUID();
        var document = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF,
                "verification/" + doctor.getId() + "/id-proof/" + docId,
                "image/png", 1024, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Instant.now()
        );
        documents.save(document);

        // System role must not have actorUser
        assertThatThrownBy(() -> new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_PURGED, doctor, document,
                VerificationDocumentKind.ID_PROOF, doctorUser,
                VerificationActorRole.SYSTEM, DocumentEventOutcome.SUCCESS,
                UUID.randomUUID().toString(), "Purge", Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);

        // Doctor role must have actorUser
        assertThatThrownBy(() -> new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_ATTACHED, doctor, document,
                VerificationDocumentKind.ID_PROOF, null,
                VerificationActorRole.DOCTOR, DocumentEventOutcome.SUCCESS,
                UUID.randomUUID().toString(), "Attach", Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesFileSizePolicyBoundaries() {
        UUID docId = UUID.randomUUID();
        String checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String key = "verification/" + doctor.getId() + "/id-proof/" + docId;

        // Zero file size rejected
        assertThatThrownBy(() -> new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF,
                key, "image/png", 0, checksum, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        // Negative file size rejected
        assertThatThrownBy(() -> new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF,
                key, "image/png", -1, checksum, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        // Greater than 5 MiB (5 * 1024 * 1024 + 1) rejected
        assertThatThrownBy(() -> new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF,
                key, "image/png", DoctorVerificationDocumentPolicy.MAX_FILE_SIZE_BYTES + 1L, checksum, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 5 MiB maximum limit");

        // Exactly 5 MiB (5 * 1024 * 1024 = 5242880) accepted and persisted
        var exactMaxDoc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF,
                key, "image/png", DoctorVerificationDocumentPolicy.MAX_FILE_SIZE_BYTES, checksum, Instant.now()
        );
        documents.save(exactMaxDoc);

        var retrieved = documents.findById(docId);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getFileSize()).isEqualTo(5242880L);
    }
}
