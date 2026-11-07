package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DoctorVerificationDocumentRepository extends JpaRepository<DoctorVerificationDocument, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DoctorVerificationDocument d WHERE d.id = :id")
    Optional<DoctorVerificationDocument> findByIdForUpdate(@Param("id") UUID id);

    Optional<DoctorVerificationDocument> findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
            Long doctorId, VerificationDocumentKind kind);

    Optional<DoctorVerificationDocument> findByStorageKey(String storageKey);

    List<DoctorVerificationDocument> findByDoctorIdOrderByCreatedAtDesc(Long doctorId);

    List<DoctorVerificationDocument> findByDoctorIdAndDocumentKindOrderByCreatedAtDesc(
            Long doctorId, VerificationDocumentKind kind);

    List<DoctorVerificationDocument> findByRetiredAtIsNotNullAndPurgedAtIsNullAndPurgeDeadlineBefore(Instant deadline);

    List<DoctorVerificationDocument> findByRetiredAtIsNotNullAndPurgedAtIsNullAndPurgeDeadlineBeforeOrderByPurgeDeadlineAscIdAsc(
            Instant deadline, Pageable pageable);

    boolean existsByDoctorIdAndDocumentKindAndRetiredAtIsNull(Long doctorId, VerificationDocumentKind kind);
}
