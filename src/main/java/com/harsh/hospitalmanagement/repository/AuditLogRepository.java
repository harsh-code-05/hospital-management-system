package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.AuditLog;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByTimestampDesc();

    List<AuditLog> findByEventTypeOrderByTimestampDesc(AuditEventType eventType);

    List<AuditLog> findByPerformedByEmailOrderByTimestampDesc(String performedByEmail);

    List<AuditLog> findByEventTypeAndPerformedByEmailOrderByTimestampDesc(AuditEventType eventType, String performedByEmail);

    List<AuditLog> findByEntityNameAndEntityIdOrderByTimestampDesc(String entityName, Long entityId);
}
