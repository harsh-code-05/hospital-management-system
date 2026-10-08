package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SpecializationRepository specializationRepository;

    @Mock
    private AuditLogService auditLogService;

    private DoctorService doctorService;

    @BeforeEach
    void setUp() {
        doctorService = new DoctorService(
                doctorRepository,
                userRepository,
                specializationRepository,
                auditLogService
        );
    }

    @Test
    void createDoctor_shouldSaveAndLogAuditEvent_whenValid() {
        User user = new User();
        user.setId(1L);

        Specialization spec = new Specialization();
        spec.setId(2L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(specializationRepository.findById(2L)).thenReturn(Optional.of(spec));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> {
            Doctor d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });

        DoctorRequest req = new DoctorRequest();
        req.setUserId(1L);
        req.setSpecializationId(2L);
        req.setFirstName("John");
        req.setLastName("Doe");
        req.setQualification("MD");
        req.setExperience(10);

        Doctor saved = doctorService.createDoctor(req, "admin@test.com");

        assertThat(saved.getId()).isEqualTo(10L);
        verify(auditLogService).logEvent(
                eq(AuditEventType.ADMIN_CREATED_DOCTOR),
                eq("admin@test.com"),
                eq("ROLE_ADMIN"),
                eq("Doctor"),
                eq(10L),
                contains("Admin created doctor profile"),
                isNull()
        );
    }

    @Test
    void createDoctor_shouldNotLogAuditEvent_whenUserNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        DoctorRequest req = new DoctorRequest();
        req.setUserId(1L);
        req.setSpecializationId(2L);

        assertThatThrownBy(() -> doctorService.createDoctor(req, "admin@test.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found");

        verify(doctorRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateDoctor_shouldSaveAndLogAuditEvent_whenValid() {
        Doctor doctor = new Doctor();
        doctor.setId(5L);

        Specialization spec = new Specialization();
        spec.setId(2L);

        when(doctorRepository.findById(5L)).thenReturn(Optional.of(doctor));
        when(specializationRepository.findById(2L)).thenReturn(Optional.of(spec));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorUpdateRequest req = new DoctorUpdateRequest();
        req.setSpecializationId(2L);
        req.setFirstName("Jane");
        req.setLastName("Doe");
        req.setQualification("MBBS, MD");
        req.setExperience(12);

        Doctor updated = doctorService.updateDoctor(5L, req, "admin@test.com");

        assertThat(updated.getFirstName()).isEqualTo("Jane");
        verify(auditLogService).logEvent(
                eq(AuditEventType.ADMIN_UPDATED_DOCTOR),
                eq("admin@test.com"),
                eq("ROLE_ADMIN"),
                eq("Doctor"),
                eq(5L),
                contains("Admin updated doctor profile"),
                isNull()
        );
    }

    @Test
    void updateDoctor_shouldNotLogAuditEvent_whenDoctorNotFound() {
        when(doctorRepository.findById(99L)).thenReturn(Optional.empty());

        DoctorUpdateRequest req = new DoctorUpdateRequest();

        assertThatThrownBy(() -> doctorService.updateDoctor(99L, req, "admin@test.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Doctor not found");

        verify(doctorRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }
}
