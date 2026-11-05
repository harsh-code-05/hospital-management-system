package com.harsh.hospitalmanagement.ai;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {
    @Query("SELECT m FROM AiChatMessage m WHERE m.session.id = :sessionId AND m.id < :before ORDER BY m.id DESC")
    List<AiChatMessage> findRecent(@Param("sessionId") Long sessionId, @Param("before") Long before, Pageable page);

    List<AiChatMessage> findBySession_IdAndRequestIdOrderByIdAsc(Long sessionId, UUID requestId);

    @Modifying
    @Query("DELETE FROM AiChatMessage m WHERE m.session.id = :sessionId")
    void deleteForSession(@Param("sessionId") Long sessionId);
}
