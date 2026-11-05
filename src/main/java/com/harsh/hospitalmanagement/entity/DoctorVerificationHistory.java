package com.harsh.hospitalmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Getter
@Entity
@Immutable
@Table(name = "doctor_verification_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DoctorVerificationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actorUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 16)
    private VerificationActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 16)
    private VerificationStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 16)
    private VerificationStatus newStatus;

    @JsonIgnore
    @Column(columnDefinition = "text")
    private String notes;

    @JsonIgnore
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_metadata")
    private Map<String, String> evidenceMetadata;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public DoctorVerificationHistory(Doctor doctor, User actorUser, VerificationActorRole actorRole,
                                     VerificationStatus previousStatus, VerificationStatus newStatus,
                                     String notes, Map<String, String> evidenceMetadata) {
        this.doctor = doctor;
        this.actorUser = actorUser;
        this.actorRole = actorRole;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.notes = notes;
        this.evidenceMetadata = evidenceMetadata == null ? null : Map.copyOf(evidenceMetadata);
        this.occurredAt = Instant.now();
    }
}
