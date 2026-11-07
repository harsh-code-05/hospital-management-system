package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;

import java.time.Instant;
import java.util.UUID;

public record DoctorVerificationDocumentResponse(
        UUID id,
        Long doctorId,
        VerificationDocumentKind documentKind,
        String mediaType,
        long fileSize,
        String checksum,
        Instant createdAt,
        Instant retiredAt
) {
    public static DoctorVerificationDocumentResponse from(DoctorVerificationDocument doc) {
        return new DoctorVerificationDocumentResponse(
                doc.getId(),
                doc.getDoctor().getId(),
                doc.getDocumentKind(),
                doc.getMediaType(),
                doc.getFileSize(),
                doc.getChecksum(),
                doc.getCreatedAt(),
                doc.getRetiredAt()
        );
    }
}
