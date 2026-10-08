package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.AuditLogResponse;
import com.harsh.hospitalmanagement.entity.AuditLog;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.service.AuditLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> getAllAuditLogs(
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) String userEmail) {

        List<AuditLogResponse> responses = auditLogService.getAllAuditLogs(eventType, userEmail)
                .stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditLogResponse> getAuditLogById(
            @PathVariable Long id) {

        AuditLog auditLog = auditLogService.getAuditLogById(id);

        return ResponseEntity.ok(toResponse(auditLog));
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getEventType(),
                log.getPerformedByEmail(),
                log.getPerformedByRole(),
                log.getEntityName(),
                log.getEntityId(),
                log.getDetails(),
                log.getIpAddress(),
                log.getTimestamp()
        );
    }
}
