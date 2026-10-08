package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AuditLog;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public AuditLog logEvent(
            AuditEventType eventType,
            String performedByEmail,
            String performedByRole,
            String entityName,
            Long entityId,
            String details,
            String ipAddress) {

        AuditLog log = new AuditLog();
        log.setEventType(eventType);
        log.setPerformedByEmail(performedByEmail != null ? performedByEmail : "anonymous");
        log.setPerformedByRole(performedByRole);
        log.setEntityName(entityName);
        log.setEntityId(entityId);
        log.setDetails(details);
        log.setIpAddress(ipAddress);
        log.setTimestamp(LocalDateTime.now());

        return auditLogRepository.save(log);
    }

    public List<AuditLog> getAllAuditLogs(AuditEventType eventType, String userEmail) {
        if (eventType != null && userEmail != null && !userEmail.isBlank()) {
            return auditLogRepository.findByEventTypeAndPerformedByEmailOrderByTimestampDesc(eventType, userEmail);
        } else if (eventType != null) {
            return auditLogRepository.findByEventTypeOrderByTimestampDesc(eventType);
        } else if (userEmail != null && !userEmail.isBlank()) {
            return auditLogRepository.findByPerformedByEmailOrderByTimestampDesc(userEmail);
        } else {
            return auditLogRepository.findAllByOrderByTimestampDesc();
        }
    }

    public AuditLog getAuditLogById(Long id) {
        return auditLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audit log not found"));
    }
}
