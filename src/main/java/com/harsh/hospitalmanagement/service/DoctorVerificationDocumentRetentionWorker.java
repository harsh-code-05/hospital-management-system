package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentEvent;
import com.harsh.hospitalmanagement.enums.DocumentEventOutcome;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class DoctorVerificationDocumentRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(DoctorVerificationDocumentRetentionWorker.class);

    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MAX_BATCH_SIZE = 1000;

    private final DoctorRepository doctorRepository;
    private final DoctorVerificationDocumentRepository documentRepository;
    private final DoctorVerificationDocumentEventRepository eventRepository;
    private final PlatformTransactionManager transactionManager;
    private final DocumentStorage documentStorage;

    @Value("${hms.document-storage.purge.enabled:false}")
    private boolean purgeEnabled;

    @Value("${hms.document-storage.purge-cron:-}")
    private String purgeCron = "-";

    @Autowired
    public DoctorVerificationDocumentRetentionWorker(
            DoctorRepository doctorRepository,
            DoctorVerificationDocumentRepository documentRepository,
            DoctorVerificationDocumentEventRepository eventRepository,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) DocumentStorage documentStorage
    ) {
        this.doctorRepository = doctorRepository;
        this.documentRepository = documentRepository;
        this.eventRepository = eventRepository;
        this.transactionManager = transactionManager;
        this.documentStorage = documentStorage;
    }

    @Scheduled(cron = "${hms.document-storage.purge-cron:-}")
    public void scheduledPurge() {
        if (!purgeEnabled || "-".equals(purgeCron) || documentStorage == null) {
            log.debug("Document storage retention purge is disabled; skipping scheduled execution");
            return;
        }
        int purged = purgeExpiredDocuments(Instant.now(), DEFAULT_BATCH_SIZE, UUID.randomUUID().toString());
        log.info("Completed scheduled verification document retention purge: {} documents purged", purged);
    }

    public boolean isPurgeEnabled() {
        return purgeEnabled && !"-".equals(purgeCron) && documentStorage != null;
    }

    public int purgeExpiredDocuments(Instant cutoff, String correlationId) {
        return purgeExpiredDocuments(cutoff, DEFAULT_BATCH_SIZE, correlationId);
    }

    public int purgeExpiredDocuments(Instant cutoff, int batchSize, String correlationId) {
        Instant effectiveCutoff = cutoff != null ? cutoff : Instant.now();
        int effectiveBatchSize = Math.min(Math.max(1, batchSize), MAX_BATCH_SIZE);
        String effectiveCorrelationId = correlationId != null && !correlationId.isBlank()
                ? DoctorVerificationDocumentService.sanitizeCorrelationId(correlationId)
                : UUID.randomUUID().toString();

        List<DoctorVerificationDocument> expiredCandidates = documentRepository
                .findByRetiredAtIsNotNullAndPurgedAtIsNullAndPurgeDeadlineBeforeOrderByPurgeDeadlineAscIdAsc(
                        effectiveCutoff, PageRequest.of(0, effectiveBatchSize));

        if (expiredCandidates.isEmpty()) {
            return 0;
        }

        int purgedCount = 0;
        for (DoctorVerificationDocument candidate : expiredCandidates) {
            try {
                boolean purged = purgeSingleDocument(candidate.getId(), effectiveCutoff, effectiveCorrelationId);
                if (purged) {
                    purgedCount++;
                }
            } catch (Exception e) {
                log.error("Failed to purge expired document ID {}: {}", candidate.getId(), e.getMessage(), e);
            }
        }
        return purgedCount;
    }

    private boolean purgeSingleDocument(UUID documentId, Instant now, String correlationId) {
        if (transactionManager == null) {
            return purgeInternal(documentId, now, correlationId);
        }
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(txTemplate.execute(status -> purgeInternal(documentId, now, correlationId)));
    }

    private boolean purgeInternal(UUID documentId, Instant now, String correlationId) {
        DoctorVerificationDocument doc = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (doc == null || doc.isPurged() || !doc.isRetired()) {
            return false;
        }

        if (doc.getPurgeDeadline() != null && doc.getPurgeDeadline().isAfter(now)) {
            return false;
        }

        // Lock doctor to serialize against active attachments
        Doctor doctor = doctorRepository.findByIdForUpdate(doc.getDoctor().getId()).orElse(null);
        if (doctor == null) {
            log.warn("Cannot purge document {}: associated doctor not found", documentId);
            return false;
        }

        String storageKey = doc.getStorageKey();

        // Safety check: verify storage key is not referenced by doctor profile
        if (storageKey.equals(doctor.getIdProofDocumentPath()) || storageKey.equals(doctor.getQualificationDocumentPath())) {
            log.warn("Cannot purge document {}: storage key is still referenced by doctor profile", documentId);
            return false;
        }

        // Safety check: verify no active document catalog row references this storage key
        var activeDocOpt = documentRepository.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), doc.getDocumentKind());
        if (activeDocOpt.isPresent() && storageKey.equals(activeDocOpt.get().getStorageKey())) {
            log.warn("Cannot purge document {}: storage key is still referenced by an active document", documentId);
            return false;
        }

        // Delete physical file from storage if present
        if (documentStorage != null) {
            try {
                DocumentKey key = DocumentKey.parse(storageKey);
                documentStorage.delete(key);
            } catch (IOException e) {
                log.error("Failed to delete physical file for storage key {}: {}", storageKey, e.getMessage());
                throw new IllegalStateException("Failed to delete physical file during retention purge", e);
            } catch (IllegalArgumentException e) {
                log.error("Corrupt storage key {} on document {}: {}", storageKey, documentId, e.getMessage());
            }
        }

        // Mark catalog record as purged
        doc.markPurged(now);
        documentRepository.save(doc);

        // Record audit event: DOCUMENT_PURGED with actorRole = SYSTEM and actorUser = null
        DoctorVerificationDocumentEvent event = new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_PURGED,
                doctor,
                doc,
                doc.getDocumentKind(),
                null,
                VerificationActorRole.SYSTEM,
                DocumentEventOutcome.SUCCESS,
                correlationId,
                "Purged expired " + doc.getDocumentKind().name() + " document after retention period",
                now
        );
        eventRepository.save(event);

        return true;
    }
}
