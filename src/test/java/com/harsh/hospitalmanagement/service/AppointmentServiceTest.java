package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.assertj.core.api.Assertions.assertThat;
import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
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
        user.setId(account(email).id());
        user.setRole(Role.PATIENT);

        Patient patient = new Patient();
        patient.setId(id);
        patient.setUser(user);
        return patient;
    }

    private Doctor createDoctor(Long id, String email) {
        User user = new User();
        user.setEmail(email);
        user.setId(account(email).id());
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

    private AppointmentSlot createPastSlot(Long id, Doctor doctor, SlotStatus status) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setId(id);
        slot.setDoctor(doctor);
        slot.setDate(LocalDate.now().minusDays(1));
        slot.setStartTime(LocalTime.of(10, 0));
        slot.setEndTime(LocalTime.of(10, 30));
        slot.setStatus(status);
        return slot;
    }

    private Appointment createAppointment(Long id, Patient patient, Doctor doctor, AppointmentSlot slot, AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setId(id);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setSlot(slot);
        appointment.setStatus(status);
        appointment.setReason("General Checkup");
        return appointment;
    }

    private void givenLockedAppointment(Appointment appointment) {
        Doctor doctor = appointment.getDoctor();
        when(appointmentRepository.findDoctorIdById(appointment.getId())).thenReturn(Optional.of(doctor.getId()));
        when(doctorRepository.findByIdForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        when(appointmentRepository.findByIdForUpdate(appointment.getId())).thenReturn(Optional.of(appointment));
    }

    private void givenLockedSlot(AppointmentSlot slot) {
        when(appointmentSlotRepository.findByIdForUpdate(slot.getId())).thenReturn(Optional.of(slot));
    }

    private void givenBookingSlot(AppointmentSlot slot) {
        Doctor doctor = slot.getDoctor();
        when(appointmentSlotRepository.findDoctorIdById(slot.getId())).thenReturn(Optional.of(doctor.getId()));
        when(doctorRepository.findByIdForUpdate(doctor.getId())).thenReturn(Optional.of(doctor));
        givenLockedSlot(slot);
    }

    // -------------------------------------------------------------------------
    // getAppointments Tests
    // -------------------------------------------------------------------------

    @Test
    void getAppointments_shouldReturnAllAppointments_whenRoleIsAdmin() {
        Appointment app1 = new Appointment();
        Appointment app2 = new Appointment();
        when(appointmentRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(app1, app2));

        List<Appointment> result = appointmentService.getAppointments(account("admin@test.com"), "ROLE_ADMIN");

        assertThat(result).containsExactly(app1, app2);
        verify(appointmentRepository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void getAppointments_shouldReturnPatientAppointments_whenRoleIsPatient() {
        Appointment app = new Appointment();
        when(appointmentRepository.findByPatient_User_IdOrderByCreatedAtDesc(account("patient@test.com").id()))
                .thenReturn(List.of(app));

        List<Appointment> result = appointmentService.getAppointments(account("patient@test.com"), "ROLE_PATIENT");

        assertThat(result).containsExactly(app);
        verify(appointmentRepository).findByPatient_User_IdOrderByCreatedAtDesc(account("patient@test.com").id());
    }

    @Test
    void getAppointments_shouldReturnDoctorAppointments_whenRoleIsDoctor() {
        Appointment app = new Appointment();
        when(appointmentRepository.findByDoctor_User_IdOrderByCreatedAtDesc(account("doctor@test.com").id()))
                .thenReturn(List.of(app));

        List<Appointment> result = appointmentService.getAppointments(account("doctor@test.com"), "ROLE_DOCTOR");

        assertThat(result).containsExactly(app);
        verify(appointmentRepository).findByDoctor_User_IdOrderByCreatedAtDesc(account("doctor@test.com").id());
    }

    @Test
    void getAppointments_shouldThrowForbiddenException_whenRoleIsUnknown() {
        assertThatThrownBy(() -> appointmentService.getAppointments(account("user@test.com"), "ROLE_GUEST"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are not allowed to access appointments");
    }

    // -------------------------------------------------------------------------
    // getAppointmentById Tests
    // -------------------------------------------------------------------------

    @Test
    void getAppointmentById_shouldReturnAppointment_whenCallerIsAdmin() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(10L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        when(appointmentRepository.findById(10L)).thenReturn(Optional.of(appointment));

        Appointment result = appointmentService.getAppointmentById(10L, account("admin@test.com"), "ROLE_ADMIN");

        assertThat(result).isEqualTo(appointment);
    }

    @Test
    void getAppointmentById_shouldReturnAppointment_whenCallerIsPatientOwner() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(10L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        when(appointmentRepository.findById(10L)).thenReturn(Optional.of(appointment));

        Appointment result = appointmentService.getAppointmentById(10L, account("patient@test.com"), "ROLE_PATIENT");

        assertThat(result).isEqualTo(appointment);
    }

    @Test
    void getAppointmentById_shouldReturnAppointment_whenCallerIsDoctorOwner() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(10L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        when(appointmentRepository.findById(10L)).thenReturn(Optional.of(appointment));

        Appointment result = appointmentService.getAppointmentById(10L, account("doctor@test.com"), "ROLE_DOCTOR");

        assertThat(result).isEqualTo(appointment);
    }

    @Test
    void getAppointmentById_shouldThrowForbiddenException_whenCallerIsUnauthorized() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(10L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        when(appointmentRepository.findById(10L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> appointmentService.getAppointmentById(10L, account("other@test.com"), "ROLE_PATIENT"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are not allowed to access this appointment");
    }

    @Test
    void getAppointmentById_shouldThrowResourceNotFoundException_whenNotFound() {
        when(appointmentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.getAppointmentById(999L, account("admin@test.com"), "ROLE_ADMIN"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Appointment not found");
    }

    // -------------------------------------------------------------------------
    // bookAppointment Tests
    // -------------------------------------------------------------------------

    @Test
    void bookAppointment_shouldSaveAndLogAuditEvent_whenValid() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.AVAILABLE);

        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(doctor));
        givenBookingSlot(slot);
        when(appointmentSlotRepository.isBookable(3L)).thenReturn(true);
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment app = inv.getArgument(0);
            app.setId(10L);
            return app;
        });

        Appointment booked = appointmentService.bookAppointment(1L, 2L, 3L, slot.getDate(), "Checkup", account("patient@test.com"));

        assertThat(booked.getId()).isEqualTo(10L);
        assertThat(booked.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);

        verify(appointmentSlotRepository).findByIdForUpdate(3L);
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
        givenBookingSlot(slot);

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 2L, 3L, slot.getDate(), "Checkup", account("patient@test.com")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Appointment slot is not available");

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void bookAppointment_shouldNotLogAuditEvent_whenPatientMismatch() {
        Patient patient = createPatient(1L, "other@test.com");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 2L, 3L, LocalDate.now().plusDays(1), "Checkup", account("patient@test.com")))
                .isInstanceOf(ForbiddenException.class);

        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void bookAppointment_shouldThrowBadRequest_whenSlotIsInThePast() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot pastSlot = createPastSlot(3L, doctor, SlotStatus.AVAILABLE);

        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(doctor));
        givenBookingSlot(pastSlot);

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 2L, 3L, pastSlot.getDate(), "Checkup", account("patient@test.com")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Cannot book an appointment slot in the past");

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void bookAppointment_shouldThrowBadRequest_whenSlotDoctorDoesNotMatch() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctorRequested = createDoctor(2L, "doctor2@test.com");
        Doctor slotOwner = createDoctor(99L, "doctor99@test.com");
        AppointmentSlot slot = createSlot(3L, slotOwner, SlotStatus.AVAILABLE);

        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(doctorRequested));
        givenBookingSlot(slot);

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 2L, 3L, slot.getDate(), "Checkup", account("patient@test.com")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Slot does not belong to this doctor");

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void bookAppointment_shouldAllowRebooking_whenSlotWasPreviouslyCancelledAndIsNowAvailable() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        // Previously cancelled slot returned to AVAILABLE
        AppointmentSlot releasedSlot = createSlot(5L, doctor, SlotStatus.AVAILABLE);

        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(doctor));
        givenBookingSlot(releasedSlot);
        when(appointmentSlotRepository.isBookable(5L)).thenReturn(true);
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment app = inv.getArgument(0);
            app.setId(55L);
            return app;
        });

        Appointment rebooked = appointmentService.bookAppointment(1L, 2L, 5L, releasedSlot.getDate(), "Follow-up", account("patient@test.com"));

        assertThat(rebooked.getId()).isEqualTo(55L);
        assertThat(releasedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        verify(appointmentRepository).save(any(Appointment.class));
    }

    @Test
    void bookAppointment_shouldThrowNotFound_whenPatientNotFound() {
        when(patientRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.bookAppointment(99L, 2L, 3L, LocalDate.now().plusDays(1), "Checkup", account("patient@test.com")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Patient not found");
    }

    @Test
    void bookAppointment_shouldThrowNotFound_whenDoctorNotFound() {
        Patient patient = createPatient(1L, "patient@test.com");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 99L, 3L, LocalDate.now().plusDays(1), "Checkup", account("patient@test.com")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Doctor not found");
    }

    @Test
    void bookAppointment_shouldThrowNotFound_whenSlotNotFound() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(doctor));
        when(appointmentSlotRepository.findDoctorIdById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.bookAppointment(1L, 2L, 99L, LocalDate.now().plusDays(1), "Checkup", account("patient@test.com")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Appointment slot not found");
    }

    // -------------------------------------------------------------------------
    // cancelAppointment Tests
    // -------------------------------------------------------------------------

    @Test
    void cancelAppointment_shouldSaveAndLogAuditEvent_whenValidPatient() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);
        givenLockedSlot(slot);
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        Appointment cancelled = appointmentService.cancelAppointment(20L, account("patient@test.com"), "ROLE_PATIENT");

        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        var order = inOrder(doctorRepository, appointmentRepository, appointmentSlotRepository);
        order.verify(doctorRepository).findByIdForUpdate(2L);
        order.verify(appointmentRepository).findByIdForUpdate(20L);
        order.verify(appointmentSlotRepository).findByIdForUpdate(3L);
        order.verify(appointmentRepository).save(appointment);
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
    void cancelAppointment_shouldSaveAndLogDoctorCancelled_whenCancelledByDoctor() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);
        givenLockedSlot(slot);
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        Appointment cancelled = appointmentService.cancelAppointment(20L, account("doctor@test.com"), "ROLE_DOCTOR");

        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        verify(auditLogService).logEvent(
                eq(AuditEventType.DOCTOR_CANCELLED_APPOINTMENT),
                eq("doctor@test.com"),
                eq("ROLE_DOCTOR"),
                eq("Appointment"),
                eq(20L),
                contains("Cancelled appointment"),
                isNull()
        );
    }

    @Test
    void cancelAppointment_shouldSaveAndLogAdminCancelled_whenCancelledByAdmin() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);
        givenLockedSlot(slot);
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        Appointment cancelled = appointmentService.cancelAppointment(20L, account("admin@test.com"), "ROLE_ADMIN");

        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        verify(auditLogService).logEvent(
                eq(AuditEventType.ADMIN_CANCELLED_APPOINTMENT),
                eq("admin@test.com"),
                eq("ROLE_ADMIN"),
                eq("Appointment"),
                eq(20L),
                contains("Cancelled appointment"),
                isNull()
        );
    }

    @Test
    void cancelAppointment_shouldThrowForbidden_whenCallerIsUnauthorized() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.cancelAppointment(20L, account("stranger@test.com"), "ROLE_PATIENT"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are not allowed to cancel this appointment");

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelAppointment_shouldNotLogAuditEvent_whenAppointmentAlreadyCancelled() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.AVAILABLE);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, AppointmentStatus.CANCELLED);

        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.cancelAppointment(20L, account("patient@test.com"), "ROLE_PATIENT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only confirmed appointments can be cancelled");

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelAppointment_shouldThrowNotFound_whenAppointmentDoesNotExist() {
        when(appointmentRepository.findDoctorIdById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.cancelAppointment(999L, account("patient@test.com"), "ROLE_PATIENT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Appointment not found");
    }

    // -------------------------------------------------------------------------
    // completeAppointment Tests
    // -------------------------------------------------------------------------

    @Test
    void completeAppointment_shouldSucceedAndLogAuditEvent_whenCompletedByDoctorOwner() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot pastSlot = createPastSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, pastSlot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);
        givenLockedSlot(pastSlot);
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        Appointment completed = appointmentService.completeAppointment(30L, account("doctor@test.com"), "ROLE_DOCTOR");

        assertThat(completed.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(pastSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        var order = inOrder(doctorRepository, appointmentRepository, appointmentSlotRepository);
        order.verify(doctorRepository).findByIdForUpdate(2L);
        order.verify(appointmentRepository).findByIdForUpdate(30L);
        order.verify(appointmentSlotRepository).findByIdForUpdate(3L);
        order.verify(appointmentRepository).save(appointment);
        verify(auditLogService).logEvent(
                eq(AuditEventType.DOCTOR_COMPLETED_APPOINTMENT),
                eq("doctor@test.com"),
                eq("ROLE_DOCTOR"),
                eq("Appointment"),
                eq(30L),
                contains("Completed appointment"),
                isNull()
        );
    }

    @Test
    void completeAppointment_shouldSucceedAndLogAuditEvent_whenCompletedByAdmin() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot pastSlot = createPastSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, pastSlot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);
        givenLockedSlot(pastSlot);
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

        Appointment completed = appointmentService.completeAppointment(30L, account("admin@test.com"), "ROLE_ADMIN");

        assertThat(completed.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(pastSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        var order = inOrder(appointmentRepository, appointmentSlotRepository);
        order.verify(appointmentRepository).findByIdForUpdate(30L);
        order.verify(appointmentSlotRepository).findByIdForUpdate(3L);
        order.verify(appointmentRepository).save(appointment);
        verify(auditLogService).logEvent(
                eq(AuditEventType.ADMIN_COMPLETED_APPOINTMENT),
                eq("admin@test.com"),
                eq("ROLE_ADMIN"),
                eq("Appointment"),
                eq(30L),
                contains("Completed appointment"),
                isNull()
        );
    }

    @Test
    void completeAppointment_shouldThrowForbidden_whenCallerIsPatient() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot pastSlot = createPastSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, pastSlot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("patient@test.com"), "ROLE_PATIENT"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the assigned doctor or admin can complete this appointment");

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completeAppointment_shouldThrowForbidden_whenCallerIsNonOwnerDoctor() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot pastSlot = createPastSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, pastSlot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("otherdoc@test.com"), "ROLE_DOCTOR"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the assigned doctor or admin can complete this appointment");

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void completeAppointment_shouldThrowBadRequest_whenSlotEndTimeIsInFuture() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot futureSlot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, futureSlot, AppointmentStatus.CONFIRMED);

        givenLockedAppointment(appointment);
        givenLockedSlot(futureSlot);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("doctor@test.com"), "ROLE_DOCTOR"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Cannot complete an appointment before its scheduled end time");

        verify(appointmentRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completeAppointment_shouldThrowBadRequest_whenAlreadyCompleted() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createPastSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, slot, AppointmentStatus.COMPLETED);

        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("doctor@test.com"), "ROLE_DOCTOR"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only confirmed appointments can be completed");

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void completeAppointment_shouldThrowBadRequest_whenAlreadyCancelled() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createPastSlot(3L, doctor, SlotStatus.AVAILABLE);
        Appointment appointment = createAppointment(30L, patient, doctor, slot, AppointmentStatus.CANCELLED);

        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("doctor@test.com"), "ROLE_DOCTOR"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only confirmed appointments can be completed");

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void completeAppointment_shouldThrowNotFound_whenAppointmentDoesNotExist() {
        when(appointmentRepository.findDoctorIdById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.completeAppointment(999L, account("doctor@test.com"), "ROLE_DOCTOR"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Appointment not found");
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    void cancelAppointment_shouldCheckAuthorizationBeforeStatus(AppointmentStatus status) {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, status);
        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.cancelAppointment(20L, account("stranger@test.com"), "ROLE_PATIENT"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You are not allowed to cancel this appointment");

        verify(appointmentRepository, never()).findById(anyLong());
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(appointmentSlotRepository, auditLogService);
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus.class)
    void completeAppointment_shouldCheckAuthorizationBeforeStatus(AppointmentStatus status) {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createPastSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, slot, status);
        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("otherdoc@test.com"), "ROLE_DOCTOR"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the assigned doctor or admin can complete this appointment");

        verify(appointmentRepository, never()).findById(anyLong());
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(appointmentSlotRepository, auditLogService);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void cancelAppointment_shouldRejectLockedTerminalStatus(AppointmentStatus status) {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, status);
        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.cancelAppointment(20L, account("admin@test.com"), "ROLE_ADMIN"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only confirmed appointments can be cancelled");

        verify(appointmentRepository, never()).findById(anyLong());
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(appointmentSlotRepository, auditLogService);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void completeAppointment_shouldRejectLockedTerminalStatus(AppointmentStatus status) {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createPastSlot(3L, doctor, SlotStatus.BOOKED);
        Appointment appointment = createAppointment(30L, patient, doctor, slot, status);
        givenLockedAppointment(appointment);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("admin@test.com"), "ROLE_ADMIN"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only confirmed appointments can be completed");

        verify(appointmentRepository, never()).findById(anyLong());
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(appointmentSlotRepository, auditLogService);
    }

    @Test
    void cancelAppointment_shouldRejectUnexpectedAvailableSlotWithoutMutation() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createSlot(3L, doctor, SlotStatus.AVAILABLE);
        Appointment appointment = createAppointment(20L, patient, doctor, slot, AppointmentStatus.CONFIRMED);
        givenLockedAppointment(appointment);
        givenLockedSlot(slot);

        assertThatThrownBy(() -> appointmentService.cancelAppointment(20L, account("admin@test.com"), "ROLE_ADMIN"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Appointment slot is not booked");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void completeAppointment_shouldRejectUnexpectedAvailableSlotWithoutMutation() {
        Patient patient = createPatient(1L, "patient@test.com");
        Doctor doctor = createDoctor(2L, "doctor@test.com");
        AppointmentSlot slot = createPastSlot(3L, doctor, SlotStatus.AVAILABLE);
        Appointment appointment = createAppointment(30L, patient, doctor, slot, AppointmentStatus.CONFIRMED);
        givenLockedAppointment(appointment);
        givenLockedSlot(slot);

        assertThatThrownBy(() -> appointmentService.completeAppointment(30L, account("admin@test.com"), "ROLE_ADMIN"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Appointment slot is not booked");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

}
