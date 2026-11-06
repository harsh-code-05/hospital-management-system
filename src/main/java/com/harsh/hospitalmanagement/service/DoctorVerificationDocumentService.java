package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentEvent;
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
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DoctorVerificationDocumentService implements DoctorVerificationDocumentOrphanReconciliation {

    public static final Duration DEFAULT_RETENTION_PERIOD = Duration.ofDays(30);

    private final DoctorRepository doctorRepository;
    private final DoctorVerificationDocumentRepository documentRepository;
    private final DoctorVerificationDocumentEventRepository eventRepository;
    private final DoctorManagementAccess access;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;
    private final DocumentStorage documentStorage;

    @Autowired
    public DoctorVerificationDocumentService(
            DoctorRepository doctorRepository,
            DoctorVerificationDocumentRepository documentRepository,
            DoctorVerificationDocumentEventRepository eventRepository,
            DoctorManagementAccess access,
            UserRepository userRepository,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) DocumentStorage documentStorage
    ) {
        this.doctorRepository = doctorRepository;
        this.documentRepository = documentRepository;
        this.eventRepository = eventRepository;
        this.access = access;
        this.userRepository = userRepository;
        this.transactionManager = transactionManager;
        this.documentStorage = documentStorage;
    }

    public DoctorVerificationDocumentService(
            DoctorRepository doctorRepository,
            DoctorVerificationDocumentRepository documentRepository,
            DoctorVerificationDocumentEventRepository eventRepository,
            DoctorManagementAccess access,
            UserRepository userRepository,
            @Autowired(required = false) DocumentStorage documentStorage
    ) {
        this(doctorRepository, documentRepository, eventRepository, access, userRepository, null, documentStorage);
    }

    /**
     * Attaches or replaces a verification document using an authenticated doctor or admin session.
     */
    public DoctorVerificationDocument attachDocument(
            Long doctorId,
            VerificationDocumentKind kind,
            ValidatedImage image,
            Authentication authentication,
            String correlationId
    ) {
        Objects.requireNonNull(doctorId, "Doctor ID must not be null");
        Objects.requireNonNull(kind, "Verification document kind must not be null");
        Objects.requireNonNull(image, "Validated image must not be null");

        var managerAccess = access.requireManager(doctorId, authentication);
        Doctor doctor = managerAccess.doctor();
        User actor = managerAccess.actor();
        VerificationActorRole actorRole = actor.getRole() == Role.ADMIN
                ? VerificationActorRole.ADMIN : VerificationActorRole.DOCTOR;

        return attachDocumentInternal(doctor, kind, image, actor, actorRole, sanitizeCorrelationId(correlationId));
    }

    /**
     * Attaches or replaces a verification document for internal or system service callers.
     */
    public DoctorVerificationDocument attachDocument(
            Long doctorId,
            VerificationDocumentKind kind,
            ValidatedImage image,
            User actorUser,
            VerificationActorRole actorRole,
            String correlationId
    ) {
        Objects.requireNonNull(doctorId, "Doctor ID must not be null");
        Objects.requireNonNull(kind, "Verification document kind must not be null");
        Objects.requireNonNull(image, "Validated image must not be null");
        Objects.requireNonNull(actorRole, "Actor role must not be null");

        Doctor doctor = doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with ID: " + doctorId));

        if (actorRole == VerificationActorRole.DOCTOR) {
            if (actorUser == null || !actorUser.getId().equals(doctor.getUser().getId())) {
                throw new ForbiddenException("You are not allowed to manage this doctor");
            }
        }

        return attachDocumentInternal(doctor, kind, image, actorUser, actorRole, sanitizeCorrelationId(correlationId));
    }

    private DoctorVerificationDocument attachDocumentInternal(
            Doctor doctor,
            VerificationDocumentKind kind,
            ValidatedImage image,
            User actorUser,
            VerificationActorRole actorRole,
            String correlationId
    ) {
        requireDraft(doctor);
        DocumentStorage storage = requireStorage();

        // Step 1: Write encrypted file to storage before publishing catalog reference
        DocumentKey key;
        try {
            key = storage.store(doctor.getId(), kind.toServiceKind(), image);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write encrypted document to private storage", exception);
        }

        Instant now = Instant.now();
        Instant purgeDeadline = now.plus(DEFAULT_RETENTION_PERIOD);
        String storageKey = key.toString();

        // Step 2: Retire any existing active document of the same kind
        Optional<DoctorVerificationDocument> activeOpt = documentRepository
                .findByDoctorIdAndDocumentKindAndRetiredAtIsNull(doctor.getId(), kind);
        DocumentEventType eventType;
        if (activeOpt.isPresent()) {
            DoctorVerificationDocument existing = activeOpt.get();
            existing.retire(now, purgeDeadline);
            documentRepository.saveAndFlush(existing);
            eventType = DocumentEventType.DOCUMENT_REPLACED;
        } else {
            eventType = DocumentEventType.DOCUMENT_ATTACHED;
        }

        // Step 3: Insert new document catalog record
        DoctorVerificationDocument document = new DoctorVerificationDocument(
                key.version(), doctor, kind, storageKey,
                image.contentType(), image.size(), image.sha256Hex(), now
        );
        document = documentRepository.save(document);

        // Step 4: Update Doctor reference field
        if (kind == VerificationDocumentKind.ID_PROOF) {
            doctor.setIdProofDocumentPath(storageKey);
        } else {
            doctor.setQualificationDocumentPath(storageKey);
        }
        doctorRepository.save(doctor);

        // Step 5: Append immutable audit event
        String details = (eventType == DocumentEventType.DOCUMENT_ATTACHED ? "Attached initial " : "Replaced active ")
                + kind.name() + " document";
        DoctorVerificationDocumentEvent event = new DoctorVerificationDocumentEvent(
                eventType, doctor, document, kind, actorUser, actorRole,
                DocumentEventOutcome.SUCCESS, correlationId, details, now
        );
        eventRepository.save(event);

        return document;
    }

    /**
     * Detaches an active verification document using an authenticated doctor or admin session.
     */
    public DoctorVerificationDocument detachDocument(
            Long doctorId,
            VerificationDocumentKind kind,
            Authentication authentication,
            String correlationId
    ) {
        Objects.requireNonNull(doctorId, "Doctor ID must not be null");
        Objects.requireNonNull(kind, "Verification document kind must not be null");

        var managerAccess = access.requireManager(doctorId, authentication);
        Doctor doctor = managerAccess.doctor();
        User actor = managerAccess.actor();
        VerificationActorRole actorRole = actor.getRole() == Role.ADMIN
                ? VerificationActorRole.ADMIN : VerificationActorRole.DOCTOR;

        return detachDocumentInternal(doctor, kind, actor, actorRole, sanitizeCorrelationId(correlationId));
    }

    /**
     * Detaches an active verification document for internal or system service callers.
     */
    public DoctorVerificationDocument detachDocument(
            Long doctorId,
            VerificationDocumentKind kind,
            User actorUser,
            VerificationActorRole actorRole,
            String correlationId
    ) {
        Objects.requireNonNull(doctorId, "Doctor ID must not be null");
        Objects.requireNonNull(kind, "Verification document kind must not be null");
        Objects.requireNonNull(actorRole, "Actor role must not be null");

        Doctor doctor = doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with ID: " + doctorId));

        if (actorRole == VerificationActorRole.DOCTOR) {
            if (actorUser == null || !actorUser.getId().equals(doctor.getUser().getId())) {
                throw new ForbiddenException("You are not allowed to manage this doctor");
            }
        }

        return detachDocumentInternal(doctor, kind, actorUser, actorRole, sanitizeCorrelationId(correlationId));
    }

    private DoctorVerificationDocument detachDocumentInternal(
            Doctor doctor,
            VerificationDocumentKind kind,
            User actorUser,
            VerificationActorRole actorRole,
            String correlationId
    ) {
        requireDraft(doctor);

        DoctorVerificationDocument activeDoc = documentRepository
                .findByDoctorIdAndDocumentKindAndRetiredAtIsNull(doctor.getId(), kind)
                .orElseThrow(() -> new ResourceNotFoundException("No active " + kind + " document found to detach"));

        Instant now = Instant.now();
        Instant purgeDeadline = now.plus(DEFAULT_RETENTION_PERIOD);

        // Retire active document (file remains on disk until purge deadline)
        activeDoc.retire(now, purgeDeadline);
        documentRepository.save(activeDoc);

        // Clear Doctor reference path
        if (kind == VerificationDocumentKind.ID_PROOF) {
            doctor.setIdProofDocumentPath(null);
        } else {
            doctor.setQualificationDocumentPath(null);
        }
        doctorRepository.save(doctor);

        // Record audit event
        DoctorVerificationDocumentEvent event = new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_DETACHED, doctor, activeDoc, kind, actorUser, actorRole,
                DocumentEventOutcome.SUCCESS, correlationId, "Detached " + kind.name() + " document", now
        );
        eventRepository.save(event);

        return activeDoc;
    }

    /**
     * Reads and authenticates an active verification document with administrator authorization.
     * Enforces Admin-only access and restricts administrative review to doctors in UNDER_REVIEW status.
     */
    public DocumentStorage.ReadDocument readDocument(
            Long doctorId,
            VerificationDocumentKind kind,
            Authentication authentication,
            String correlationId
    ) {
        Objects.requireNonNull(doctorId, "Doctor ID must not be null");
        Objects.requireNonNull(kind, "Verification document kind must not be null");

        var managerAccess = access.requireManagerForRead(doctorId, authentication);
        Doctor doctor = managerAccess.doctor();
        User actor = managerAccess.actor();
        if (actor.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Only administrators may access raw verification evidence");
        }
        if (doctor.getVerificationStatus() != VerificationStatus.UNDER_REVIEW) {
            throw new ConflictException("Evidence can only be accessed for administrative review when the doctor is under review");
        }

        return readDocumentInternal(doctor, kind, actor, VerificationActorRole.ADMIN, sanitizeCorrelationId(correlationId));
    }

    /**
     * Reads and authenticates an active verification document for internal or system service callers.
     * Enforces Admin-only access and restricts administrative review to doctors in UNDER_REVIEW status.
     */
    public DocumentStorage.ReadDocument readDocument(
            Long doctorId,
            VerificationDocumentKind kind,
            User actorUser,
            VerificationActorRole actorRole,
            String correlationId
    ) {
        Objects.requireNonNull(doctorId, "Doctor ID must not be null");
        Objects.requireNonNull(kind, "Verification document kind must not be null");
        Objects.requireNonNull(actorRole, "Actor role must not be null");

        if (actorRole != VerificationActorRole.ADMIN) {
            throw new ForbiddenException("Only administrators may access raw verification evidence");
        }

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with ID: " + doctorId));

        if (doctor.getVerificationStatus() != VerificationStatus.UNDER_REVIEW) {
            throw new ConflictException("Evidence can only be accessed for administrative review when the doctor is under review");
        }

        return readDocumentInternal(doctor, kind, actorUser, actorRole, sanitizeCorrelationId(correlationId));
    }

    private DocumentStorage.ReadDocument readDocumentInternal(
            Doctor doctor,
            VerificationDocumentKind kind,
            User actorUser,
            VerificationActorRole actorRole,
            String correlationId
    ) {
        DocumentStorage storage = requireStorage();

        DoctorVerificationDocument doc = documentRepository
                .findByDoctorIdAndDocumentKindAndRetiredAtIsNull(doctor.getId(), kind)
                .orElseThrow(() -> new ResourceNotFoundException("Active " + kind + " document not found for doctor " + doctor.getId()));

        DocumentKey key;
        try {
            key = DocumentKey.parse(doc.getStorageKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Corrupt storage key recorded in document catalog: " + doc.getStorageKey(), exception);
        }

        DocumentStorage.ReadDocument readDocument;
        try {
            readDocument = storage.read(key);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read or authenticate verification document from storage", exception);
        }

        // Commit audit event before returning decrypted document bytes to caller
        if (transactionManager != null) {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txTemplate.execute(status -> {
                Doctor lockedDoctor = doctorRepository.findById(doctor.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
                DoctorVerificationDocument docRef = documentRepository.findById(doc.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
                User actorRef = actorUser != null && actorUser.getId() != null
                        ? userRepository.findById(actorUser.getId()).orElse(null) : null;
                DoctorVerificationDocumentEvent event = new DoctorVerificationDocumentEvent(
                        DocumentEventType.ADMIN_ACCESS_GRANTED, lockedDoctor, docRef, kind, actorRef, actorRole,
                        DocumentEventOutcome.SUCCESS, correlationId, "Admin accessed " + kind.name() + " document", Instant.now()
                );
                return eventRepository.save(event);
            });
        } else {
            DoctorVerificationDocumentEvent event = new DoctorVerificationDocumentEvent(
                    DocumentEventType.ADMIN_ACCESS_GRANTED, doctor, doc, kind, actorUser, actorRole,
                    DocumentEventOutcome.SUCCESS, correlationId, "Admin accessed " + kind.name() + " document", Instant.now()
            );
            eventRepository.save(event);
        }

        return readDocument;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<DoctorVerificationDocument> getActiveDocument(Long doctorId, VerificationDocumentKind kind) {
        return documentRepository.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(doctorId, kind);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<DoctorVerificationDocument> getDocumentHistory(Long doctorId, VerificationDocumentKind kind) {
        return documentRepository.findByDoctorIdAndDocumentKindOrderByCreatedAtDesc(doctorId, kind);
    }

    @Override
    public ReconciliationDecision reconcileOrphanCandidate(DocumentKey key, Instant candidateCreatedAt, Duration gracePeriod) {
        Objects.requireNonNull(key, "DocumentKey must not be null");
        Objects.requireNonNull(candidateCreatedAt, "Candidate creation timestamp must not be null");
        Objects.requireNonNull(gracePeriod, "GracePeriod must not be null");

        if (gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new IllegalArgumentException("Grace period must be positive");
        }

        DocumentStorage storage = requireStorage();
        try {
            if (!storage.exists(key)) {
                return ReconciliationDecision.STORAGE_ABSENT;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Storage error checking file existence", exception);
        }

        // Grace period check: preserve newly created unreferenced files.
        // Protected against arithmetic overflow when adding large durations.
        Instant now = Instant.now();
        boolean withinGrace;
        try {
            Instant graceDeadline = candidateCreatedAt.plus(gracePeriod);
            withinGrace = graceDeadline.isAfter(now);
        } catch (DateTimeException | ArithmeticException e) {
            withinGrace = true;
        }

        if (withinGrace) {
            return ReconciliationDecision.WITHIN_GRACE;
        }

        // Recheck under doctor lock to ensure concurrent transactions are serialized
        Doctor doctor = doctorRepository.findByIdForUpdate(key.doctorId()).orElse(null);
        String storageKey = key.toString();

        if (doctor != null) {
            if (storageKey.equals(doctor.getIdProofDocumentPath()) || storageKey.equals(doctor.getQualificationDocumentPath())) {
                return ReconciliationDecision.REFERENCED;
            }
        }

        if (documentRepository.findByStorageKey(storageKey).isPresent()) {
            return ReconciliationDecision.REFERENCED;
        }

        // Safe orphan deletion: unreferenced and outside grace period
        try {
            boolean deleted = storage.delete(key);
            return deleted ? ReconciliationDecision.ORPHAN_DELETED : ReconciliationDecision.STORAGE_ABSENT;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete unreferenced orphan document file", exception);
        }
    }

    private DocumentStorage requireStorage() {
        if (documentStorage == null) {
            throw new IllegalStateException("Document storage is disabled or unavailable");
        }
        return documentStorage;
    }

    private void requireDraft(Doctor doctor) {
        VerificationStatus status = doctor.getVerificationStatus();
        if (status != VerificationStatus.UNVERIFIED && status != VerificationStatus.REJECTED) {
            throw new ConflictException("Evidence can only be modified before submission or after rejection");
        }
    }

    public static String sanitizeCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String stripped = correlationId.strip();
        if (stripped.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        if (stripped.length() <= 64) {
            return stripped;
        }
        // Deterministic, collision-resistant compaction for correlation IDs exceeding 64 chars:
        // Preserves first 31 chars of semantic trace context + "_" delimiter + 32-char SHA-256 hex digest.
        // Total length = 31 + 1 + 32 = 64 characters.
        String hash = sha256Hex(stripped);
        return stripped.substring(0, 31) + "_" + hash.substring(0, 32);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0x0f, 16));
                hex.append(Character.forDigit(b & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
