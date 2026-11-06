package com.harsh.hospitalmanagement.entity;

import com.harsh.hospitalmanagement.enums.DocumentEventOutcome;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;

@Getter
@Entity
@Immutable
@Table(name = "doctor_verification_document_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DoctorVerificationDocumentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    private DocumentEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false, updatable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", updatable = false)
    private DoctorVerificationDocument document;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_kind", nullable = false, updatable = false, length = 32)
    private VerificationDocumentKind documentKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", updatable = false)
    private User actorUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, updatable = false, length = 16)
    private VerificationActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 16)
    private DocumentEventOutcome outcome;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    private String correlationId;

    @Column(name = "details", updatable = false, length = 500)
    private String details;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public DoctorVerificationDocumentEvent(DocumentEventType eventType, Doctor doctor,
                                           DoctorVerificationDocument document,
                                           VerificationDocumentKind documentKind,
                                           User actorUser, VerificationActorRole actorRole,
                                           DocumentEventOutcome outcome, String correlationId,
                                           String details, Instant occurredAt) {
        this.eventType = Objects.requireNonNull(eventType, "Event type must not be null");
        this.doctor = Objects.requireNonNull(doctor, "Doctor must not be null");
        this.document = document;
        this.documentKind = Objects.requireNonNull(documentKind, "Document kind must not be null");
        this.actorRole = Objects.requireNonNull(actorRole, "Actor role must not be null");
        if (actorRole == VerificationActorRole.SYSTEM && actorUser != null) {
            throw new IllegalArgumentException("System events must not have an actor user");
        }
        if (actorRole != VerificationActorRole.SYSTEM && actorUser == null) {
            throw new IllegalArgumentException("User events must specify an actor user");
        }
        this.actorUser = actorUser;
        this.outcome = Objects.requireNonNull(outcome, "Outcome must not be null");
        this.correlationId = Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        this.details = details;
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }
}
