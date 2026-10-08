package com.harsh.hospitalmanagement.entity;

import com.harsh.hospitalmanagement.enums.AuditEventType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditEventType eventType;

    @Column(nullable = false)
    private String performedByEmail;

    @Column
    private String performedByRole;

    @Column
    private String entityName;

    @Column
    private Long entityId;

    @Column(length = 1000)
    private String details;

    @Column
    private String ipAddress;

    @Column(nullable = false)
    private LocalDateTime timestamp;
}
