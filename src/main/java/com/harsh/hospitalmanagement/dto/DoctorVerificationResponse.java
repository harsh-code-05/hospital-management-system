package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationStatus;

import java.time.Instant;
import java.util.List;

public record DoctorVerificationResponse(
        Long doctorId,
        VerificationStatus status,
        String registrationNumber,
        String medicalCouncil,
        Integer registrationYear,
        List<HistoryEntry> history) {

    public record HistoryEntry(VerificationStatus previousStatus, VerificationStatus newStatus,
                               VerificationActorRole actorRole, Instant occurredAt) {
    }
}
