package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AuditLog;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository);
    }

    @Test
    void logEvent_shouldSaveAuditLogWithAllFieldsAndTimestamp() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog log = invocation.getArgument(0);
            log.setId(1L);
            return log;
        });

        AuditLog saved = auditLogService.logEvent(
                AuditEventType.PATIENT_BOOKED_APPOINTMENT,
                "patient@test.com",
                "ROLE_PATIENT",
                "Appointment",
                100L,
                "Booked appointment",
                "127.0.0.1"
        );

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getEventType()).isEqualTo(AuditEventType.PATIENT_BOOKED_APPOINTMENT);
        assertThat(saved.getPerformedByEmail()).isEqualTo("patient@test.com");
        assertThat(saved.getPerformedByRole()).isEqualTo("ROLE_PATIENT");
        assertThat(saved.getEntityName()).isEqualTo("Appointment");
        assertThat(saved.getEntityId()).isEqualTo(100L);
        assertThat(saved.getDetails()).isEqualTo("Booked appointment");
        assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(saved.getTimestamp()).isNotNull();

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.PATIENT_BOOKED_APPOINTMENT);
    }

    @Test
    void logEvent_shouldFallbackToAnonymous_whenEmailIsNull() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog saved = auditLogService.logEvent(
                AuditEventType.LOGIN_FAILURE,
                null,
                null,
                "User",
                null,
                "Failed login",
                "127.0.0.1"
        );

        assertThat(saved.getPerformedByEmail()).isEqualTo("anonymous");
    }

    @Test
    void getAllAuditLogs_shouldReturnAllLogs_whenNoFilter() {
        AuditLog log = new AuditLog(1L, AuditEventType.LOGIN_SUCCESS, "admin@test.com", "ROLE_ADMIN", "User", null, "Login", "127.0.0.1", LocalDateTime.now());
        when(auditLogRepository.findAllByOrderByTimestampDesc()).thenReturn(List.of(log));

        List<AuditLog> result = auditLogService.getAllAuditLogs(null, null);

        assertThat(result).hasSize(1);
        verify(auditLogRepository).findAllByOrderByTimestampDesc();
    }

    @Test
    void getAllAuditLogs_shouldFilterByEventType_whenEventTypeProvided() {
        AuditLog log = new AuditLog(1L, AuditEventType.ADMIN_CREATED_DOCTOR, "admin@test.com", "ROLE_ADMIN", "Doctor", 5L, "Created doctor", "127.0.0.1", LocalDateTime.now());
        when(auditLogRepository.findByEventTypeOrderByTimestampDesc(AuditEventType.ADMIN_CREATED_DOCTOR)).thenReturn(List.of(log));

        List<AuditLog> result = auditLogService.getAllAuditLogs(AuditEventType.ADMIN_CREATED_DOCTOR, null);

        assertThat(result).hasSize(1);
        verify(auditLogRepository).findByEventTypeOrderByTimestampDesc(AuditEventType.ADMIN_CREATED_DOCTOR);
    }

    @Test
    void getAllAuditLogs_shouldFilterByUserEmail_whenUserEmailProvided() {
        AuditLog log = new AuditLog(1L, AuditEventType.DOCTOR_CREATED_AVAILABILITY, "doc@test.com", "ROLE_DOCTOR", "DoctorAvailability", 10L, "Created", "127.0.0.1", LocalDateTime.now());
        when(auditLogRepository.findByPerformedByEmailOrderByTimestampDesc("doc@test.com")).thenReturn(List.of(log));

        List<AuditLog> result = auditLogService.getAllAuditLogs(null, "doc@test.com");

        assertThat(result).hasSize(1);
        verify(auditLogRepository).findByPerformedByEmailOrderByTimestampDesc("doc@test.com");
    }

    @Test
    void getAllAuditLogs_shouldFilterByBoth_whenBothProvided() {
        AuditLog log = new AuditLog(1L, AuditEventType.DOCTOR_UPDATED_AVAILABILITY, "doc@test.com", "ROLE_DOCTOR", "DoctorAvailability", 10L, "Updated", "127.0.0.1", LocalDateTime.now());
        when(auditLogRepository.findByEventTypeAndPerformedByEmailOrderByTimestampDesc(AuditEventType.DOCTOR_UPDATED_AVAILABILITY, "doc@test.com")).thenReturn(List.of(log));

        List<AuditLog> result = auditLogService.getAllAuditLogs(AuditEventType.DOCTOR_UPDATED_AVAILABILITY, "doc@test.com");

        assertThat(result).hasSize(1);
        verify(auditLogRepository).findByEventTypeAndPerformedByEmailOrderByTimestampDesc(AuditEventType.DOCTOR_UPDATED_AVAILABILITY, "doc@test.com");
    }

    @Test
    void getAuditLogById_shouldReturnLog_whenFound() {
        AuditLog log = new AuditLog(1L, AuditEventType.ADMIN_UPDATED_DOCTOR, "admin@test.com", "ROLE_ADMIN", "Doctor", 5L, "Updated doctor", "127.0.0.1", LocalDateTime.now());
        when(auditLogRepository.findById(1L)).thenReturn(Optional.of(log));

        AuditLog result = auditLogService.getAuditLogById(1L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void getAuditLogById_shouldThrowException_whenNotFound() {
        when(auditLogRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auditLogService.getAuditLogById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Audit log not found");
    }
}
