package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.storage.DocumentKey;

import java.time.Duration;
import java.time.Instant;

/**
 * Interface for safe reconciliation of potential unreferenced document files.
 * Rechecks PostgreSQL catalog under doctor pessimistic lock before deletion.
 */
public interface DoctorVerificationDocumentOrphanReconciliation {

    ReconciliationDecision reconcileOrphanCandidate(DocumentKey key, Instant candidateCreatedAt, Duration gracePeriod);

    enum ReconciliationDecision {
        REFERENCED,
        WITHIN_GRACE,
        ORPHAN_DELETED,
        STORAGE_ABSENT
    }
}
