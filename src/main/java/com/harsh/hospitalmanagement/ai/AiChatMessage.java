package com.harsh.hospitalmanagement.ai;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "ai_chat_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_chat_message_request_role",
                columnNames = {"session_id", "request_id", "role"}),
        indexes = @Index(name = "ix_ai_chat_messages_session_id", columnList = "session_id, id"))
public class AiChatMessage {
    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AiChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Role role;

    @Column(nullable = false, length = 8000, updatable = false)
    private String content;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "include_appointments", nullable = false, updatable = false)
    private boolean includeAppointments;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    AiChatMessage(AiChatSession session, Role role, String content, UUID requestId, boolean includeAppointments) {
        this.session = session;
        this.role = role;
        this.content = content;
        this.requestId = requestId;
        this.includeAppointments = includeAppointments;
        this.createdAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
