package com.harsh.hospitalmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "doctor_verification_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DoctorVerificationDocument {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false, updatable = false)
    private Doctor doctor;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_kind", nullable = false, updatable = false, length = 32)
    private VerificationDocumentKind documentKind;

    @Column(name = "storage_key", nullable = false, unique = true, updatable = false, length = 128)
    private String storageKey;

    @Column(name = "media_type", nullable = false, updatable = false, length = 64)
    private String mediaType;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(name = "checksum", nullable = false, updatable = false, length = 64)
    private String checksum;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "purge_deadline")
    private Instant purgeDeadline;

    @Column(name = "purged_at")
    private Instant purgedAt;

    public static final int MAX_FILE_SIZE_BYTES = DoctorVerificationDocumentPolicy.MAX_FILE_SIZE_BYTES;

    public DoctorVerificationDocument(UUID id, Doctor doctor, VerificationDocumentKind documentKind,
                                      String storageKey, String mediaType, long fileSize,
                                      String checksum, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Document ID must not be null");
        this.doctor = Objects.requireNonNull(doctor, "Doctor must not be null");
        this.documentKind = Objects.requireNonNull(documentKind, "Document kind must not be null");
        this.storageKey = Objects.requireNonNull(storageKey, "Storage key must not be null");
        this.mediaType = Objects.requireNonNull(mediaType, "Media type must not be null");
        DoctorVerificationDocumentPolicy.validateFileSize(fileSize);
        this.fileSize = fileSize;
        this.checksum = Objects.requireNonNull(checksum, "Checksum must not be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public boolean isActive() {
        return retiredAt == null;
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    public boolean isPurged() {
        return purgedAt != null;
    }

    public void retire(Instant retiredAt, Instant purgeDeadline) {
        if (this.retiredAt != null) {
            throw new IllegalStateException("Document is already retired");
        }
        this.retiredAt = Objects.requireNonNull(retiredAt, "Retired timestamp must not be null");
        this.purgeDeadline = purgeDeadline;
    }

    public void markPurged(Instant purgedAt) {
        if (this.retiredAt == null) {
            throw new IllegalStateException("Cannot purge an active document");
        }
        if (this.purgedAt != null) {
            throw new IllegalStateException("Document is already purged");
        }
        this.purgedAt = Objects.requireNonNull(purgedAt, "Purged timestamp must not be null");
    }
}
