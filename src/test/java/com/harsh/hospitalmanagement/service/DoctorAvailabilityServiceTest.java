package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorAvailabilityServiceTest {

    @Mock
    private DoctorAvailabilityRepository doctorAvailabilityRepository;

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private AuditLogService auditLogService;

    private DoctorAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new DoctorAvailabilityService(
                doctorAvailabilityRepository,
                doctorRepository,
                auditLogService
        );
    }

    private Doctor createDoctor(Long id, String email) {
        User user = new User();
        user.setEmail(email);
        user.setRole(Role.DOCTOR);

        Doctor doctor = new Doctor();
        doctor.setId(id);
        doctor.setUser(user);
        return doctor;
    }

    @Test
    void createAvailability_shouldSaveAndLogAuditEvent_whenValid() {
        Doctor doctor = createDoctor(1L, "doc@test.com");
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorAvailabilityRepository.existsOverlappingAvailability(anyLong(), any(), any(), any()))
                .thenReturn(false);
        when(doctorAvailabilityRepository.save(any(DoctorAvailability.class))).thenAnswer(inv -> {
            DoctorAvailability a = inv.getArgument(0);
            a.setId(10L);
            return a;
        });

        DoctorAvailabilityRequest req = new DoctorAvailabilityRequest();
        req.setDoctorId(1L);
        req.setDate(LocalDate.now().plusDays(1));
        req.setStartTime(LocalTime.of(9, 0));
        req.setEndTime(LocalTime.of(12, 0));

        DoctorAvailability saved = service.createAvailability(req, "doc@test.com", false);

        assertThat(saved.getId()).isEqualTo(10L);
        verify(auditLogService).logEvent(
                eq(AuditEventType.DOCTOR_CREATED_AVAILABILITY),
                eq("doc@test.com"),
                eq("ROLE_DOCTOR"),
                eq("DoctorAvailability"),
                eq(10L),
                contains("Created availability"),
                isNull()
        );
    }

    @Test
    void createAvailability_shouldNotLogAuditEvent_whenTimeInvalid() {
        Doctor doctor = createDoctor(1L, "doc@test.com");
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        DoctorAvailabilityRequest req = new DoctorAvailabilityRequest();
        req.setDoctorId(1L);
        req.setDate(LocalDate.now().plusDays(1));
        req.setStartTime(LocalTime.of(12, 0));
        req.setEndTime(LocalTime.of(9, 0)); // invalid: end before start

        assertThatThrownBy(() -> service.createAvailability(req, "doc@test.com", false))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Start time must be before end time");

        verify(doctorAvailabilityRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateAvailability_shouldSaveAndLogAuditEvent_whenValid() {
        Doctor doctor = createDoctor(1L, "doc@test.com");
        DoctorAvailability existing = new DoctorAvailability();
        existing.setId(5L);
        existing.setDoctor(doctor);
        existing.setDate(LocalDate.now().plusDays(2));
        existing.setStartTime(LocalTime.of(9, 0));
        existing.setEndTime(LocalTime.of(12, 0));

        when(doctorAvailabilityRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(doctorAvailabilityRepository.existsOverlappingAvailabilityExcludingId(anyLong(), any(), any(), any(), anyLong()))
                .thenReturn(false);
        when(doctorAvailabilityRepository.save(any(DoctorAvailability.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorAvailabilityRequest req = new DoctorAvailabilityRequest();
        req.setDoctorId(1L);
        req.setDate(LocalDate.now().plusDays(3));
        req.setStartTime(LocalTime.of(10, 0));
        req.setEndTime(LocalTime.of(14, 0));

        DoctorAvailability updated = service.updateAvailability(5L, req, "doc@test.com", false);

        assertThat(updated.getDate()).isEqualTo(LocalDate.now().plusDays(3));
        verify(auditLogService).logEvent(
                eq(AuditEventType.DOCTOR_UPDATED_AVAILABILITY),
                eq("doc@test.com"),
                eq("ROLE_DOCTOR"),
                eq("DoctorAvailability"),
                eq(5L),
                contains("Updated availability"),
                isNull()
        );
    }

    @Test
    void updateAvailability_shouldNotLogAuditEvent_whenUnauthorized() {
        Doctor doctor = createDoctor(1L, "doc@test.com");
        DoctorAvailability existing = new DoctorAvailability();
        existing.setId(5L);
        existing.setDoctor(doctor);

        when(doctorAvailabilityRepository.findById(5L)).thenReturn(Optional.of(existing));

        DoctorAvailabilityRequest req = new DoctorAvailabilityRequest();
        req.setDoctorId(1L);

        assertThatThrownBy(() -> service.updateAvailability(5L, req, "other@test.com", false))
                .isInstanceOf(ForbiddenException.class);

        verify(doctorAvailabilityRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }
}
