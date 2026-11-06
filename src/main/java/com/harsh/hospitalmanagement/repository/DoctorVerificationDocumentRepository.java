package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DoctorVerificationDocumentRepository extends JpaRepository<DoctorVerificationDocument, UUID> {

    Optional<DoctorVerificationDocument> findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
            Long doctorId, VerificationDocumentKind kind);

    Optional<DoctorVerificationDocument> findByStorageKey(String storageKey);

    List<DoctorVerificationDocument> findByDoctorIdOrderByCreatedAtDesc(Long doctorId);

    List<DoctorVerificationDocument> findByDoctorIdAndDocumentKindOrderByCreatedAtDesc(
            Long doctorId, VerificationDocumentKind kind);

    List<DoctorVerificationDocument> findByRetiredAtIsNotNullAndPurgedAtIsNullAndPurgeDeadlineBefore(Instant deadline);

    boolean existsByDoctorIdAndDocumentKindAndRetiredAtIsNull(Long doctorId, VerificationDocumentKind kind);
}
