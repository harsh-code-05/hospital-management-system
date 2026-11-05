package com.harsh.hospitalmanagement.ai;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {
    List<AiChatSession> findByPatient_IdOrderByUpdatedAtDescIdDesc(Long patientId, Pageable page);

    Optional<AiChatSession> findByIdAndPatient_Id(Long id, Long patientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT s FROM AiChatSession s WHERE s.id = :id AND s.patient.id = :patientId")
    Optional<AiChatSession> findOwnedForUpdate(@Param("id") Long id, @Param("patientId") Long patientId);
}
