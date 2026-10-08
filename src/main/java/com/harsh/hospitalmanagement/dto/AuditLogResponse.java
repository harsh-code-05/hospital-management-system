package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.AuditEventType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private Long id;
    private AuditEventType eventType;
    private String performedByEmail;
    private String performedByRole;
    private String entityName;
    private Long entityId;
    private String details;
    private String ipAddress;
    private LocalDateTime timestamp;
}
