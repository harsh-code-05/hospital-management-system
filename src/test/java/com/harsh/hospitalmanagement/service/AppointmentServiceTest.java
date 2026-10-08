package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
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
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AppointmentSlotRepository appointmentSlotRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private AuditLogService auditLogService;

    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(
                appointmentRepository,
                appointmentSlotRepository,
                patientRepository,
                doctorRepository,
                auditLogService
        );
    }

    private Patient createPatient(Long id, String email) {
        User user = new User();
        user.setEmail(email);
        user.setRole(Role.PATIENT);

        Patient patient = new Patient();
        patient.setId(id);
        patient.setUser(user);
        return patient;
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

    private AppointmentSlot createSlot(Long id, Doctor doctor, SlotStatus status) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setId(id);
        slot.setDoctor(doctor);
        slot.setDate(LocalDate.now().plusDays(1));
        slot.setStartTime(LocalTime.of(10, 0));
        slot.setEndTime(LocalTime.of(10, 30));
        slot.setStatus(status);
        return slot;
    }

    @Test
    void bookAppointment_shouldSaveAndLogAuditEvent_whenValid() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.AVAILABLE);

        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(doctor));
        when(appointmentSlotRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(slot));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment app = inv.getArgument(0);
            app.setId(10L);
            return app;
        });

        Appointment booked = appointmentService.bookAppointment(1L, 2L, 3L, "Checkup", "patient@test.com");

        assertThat(booked.getId()).isEqualTo(10L);
        assertThat(booked.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);

        verify(auditLogService).logEvent(
                eq(AuditEventType.PATIENT_BOOKED_APPOINTMENT),
                eq("patient@test.com"),
                eq("ROLE_PATIENT"),
                eq("Appointment"),
                eq(10L),
                contains("Booked appointment"),
                isNull()
        );
    }

    @Test
    void bookAppointment_shouldNotLogAuditEvent_whenSlotNotAvailable() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);

        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(doctor));
        when(appointmentSlotRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 2L, 3L, "Checkup", "patient@test.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Appointment slot is not available");

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void bookAppointment_shouldNotLogAuditEvent_whenPatientMismatch() {
        Patient patient = createPatient(1L, "other@test.com");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 2L, 3L, "Checkup", "patient@test.com"))
                .isInstanceOf(ForbiddenException.class);

        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelAppointment_shouldSaveAndLogAuditEvent_whenValid() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);

        Appointment appointment = new Appointment();
        appointment.setId(20L);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setSlot(slot);
        appointment.setStatus(AppointmentStatus.CONFIRMED);

        when(appointmentRepository.findById(20L)).thenReturn(Optional.of(appointment));
        when(appointmentSlotRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(slot));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        Appointment cancelled = appointmentService.cancelAppointment(20L, "patient@test.com", "ROLE_PATIENT");

        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        verify(auditLogService).logEvent(
                eq(AuditEventType.PATIENT_CANCELLED_APPOINTMENT),
                eq("patient@test.com"),
                eq("ROLE_PATIENT"),
                eq("Appointment"),
                eq(20L),
                contains("Cancelled appointment"),
                isNull()
        );
    }

    @Test
    void cancelAppointment_shouldNotLogAuditEvent_whenAppointmentAlreadyCancelled() {
        Appointment appointment = new Appointment();
        appointment.setId(20L);
        appointment.setStatus(AppointmentStatus.CANCELLED);

        when(appointmentRepository.findById(20L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentService.cancelAppointment(20L, "patient@test.com", "ROLE_PATIENT"))
                .isInstanceOf(BadRequestException.class);

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }
}
