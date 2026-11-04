package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.service.AuditLogService;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentSlotRepository appointmentSlotRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AuditLogService auditLogService;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            AppointmentSlotRepository appointmentSlotRepository,
            PatientRepository patientRepository,
            DoctorRepository doctorRepository,
            AuditLogService auditLogService) {

        this.appointmentRepository = appointmentRepository;
        this.appointmentSlotRepository = appointmentSlotRepository;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.auditLogService = auditLogService;
    }

    public Appointment getAppointmentById(
            Long id,
            LocalAccount actor,
            String role) {

        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment not found"));

        if (role.equals("ROLE_ADMIN")) {
            return appointment;
        }

        Long patientUserId = appointment.getPatient()
                .getUser()
                .getId();

        Long doctorUserId = appointment.getDoctor()
                .getUser()
                .getId();

        boolean patientOwner = actor.id() == patientUserId;
        boolean doctorOwner = actor.id() == doctorUserId;

        if (!patientOwner && !doctorOwner) {
            throw new ForbiddenException(
                    "You are not allowed to access this appointment");
        }

        return appointment;
    }

    public List<Appointment> getAppointments(
            LocalAccount actor,
            String role) {

        if (role.equals("ROLE_ADMIN")) {
            return appointmentRepository
                    .findAllByOrderByCreatedAtDesc();
        }

        if (role.equals("ROLE_PATIENT")) {
            return appointmentRepository
                    .findByPatient_User_IdOrderByCreatedAtDesc(
                            actor.id());
        }

        if (role.equals("ROLE_DOCTOR")) {
            return appointmentRepository
                    .findByDoctor_User_IdOrderByCreatedAtDesc(
                            actor.id());
        }

        throw new ForbiddenException(
                "You are not allowed to access appointments");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment bookAppointment(
            Long patientId,
            Long doctorId,
            Long slotId,
            String reason,
            LocalAccount actor) {

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Patient not found"));

        Long patientUserId = patient.getUser().getId();

        if (actor.id() != patientUserId) {
            throw new ForbiddenException(
                    "You are not allowed to book an appointment for this patient");
        }

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Doctor not found"));

        long slotDoctorId = appointmentSlotRepository.findDoctorIdById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment slot not found"));
        lockDoctorSchedule(slotDoctorId);

        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Appointment slot not found"));

        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            throw new BadRequestException(
                    "Appointment slot is not available");
        }

        if (slot.getDoctor().getId() != slotDoctorId || !slot.getDoctor().getId().equals(doctor.getId())) {
            throw new BadRequestException(
                    "Slot does not belong to this doctor");
        }

        LocalDateTime slotStart = LocalDateTime.of(slot.getDate(), slot.getStartTime());
        if (slotStart.isBefore(LocalDateTime.now())) {
            throw new BadRequestException(
                    "Cannot book an appointment slot in the past");
        }

        if (!appointmentSlotRepository.isBookable(slotId)) {
            throw new BadRequestException("Appointment slot is not available");
        }

        if (appointmentRepository.existsOverlappingConfirmedAppointment(
                slotDoctorId, slot.getDate(), slot.getStartTime(), slot.getEndTime())) {
            throw new BadRequestException("Doctor already has an appointment during this time");
        }

        slot.setStatus(SlotStatus.BOOKED);

        Appointment appointment = new Appointment();

        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setSlot(slot);
        appointment.setReason(reason);
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setCreatedAt(LocalDateTime.now());

        Appointment savedAppointment = appointmentRepository.save(appointment);

        auditLogService.logEvent(
                AuditEventType.PATIENT_BOOKED_APPOINTMENT,
                actor.email(),
                "ROLE_PATIENT",
                "Appointment",
                savedAppointment.getId(),
                "Booked appointment ID " + savedAppointment.getId() + " with Doctor ID " + doctor.getId() + " for Slot ID " + slot.getId(),
                null
        );

        return savedAppointment;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment cancelAppointment(
            Long appointmentId,
            LocalAccount actor,
            String role) {

        Appointment appointment = lockAppointmentSchedule(appointmentId);

        boolean admin = role.equals("ROLE_ADMIN");

        Long patientUserId = appointment.getPatient()
                .getUser()
                .getId();

        Long doctorUserId = appointment.getDoctor()
                .getUser()
                .getId();

        boolean patientOwner = actor.id() == patientUserId;
        boolean doctorOwner = actor.id() == doctorUserId;

        if (!admin && !patientOwner && !doctorOwner) {
            throw new ForbiddenException(
                    "You are not allowed to cancel this appointment");
        }

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new BadRequestException(
                    "Only confirmed appointments can be cancelled");
        }

        // The doctor and appointment locks are already held, so the slot is last.
        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(
                        appointment.getSlot().getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Appointment slot not found"));

        if (slot.getStatus() != SlotStatus.BOOKED) {
            throw new BadRequestException("Appointment slot is not booked");
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        // Release occupancy. A retired slot stays withdrawn until explicitly republished.
        slot.setStatus(SlotStatus.AVAILABLE);

        Appointment savedAppointment = appointmentRepository.save(appointment);

        AuditEventType eventType;
        if (admin) {
            eventType = AuditEventType.ADMIN_CANCELLED_APPOINTMENT;
        } else if (doctorOwner) {
            eventType = AuditEventType.DOCTOR_CANCELLED_APPOINTMENT;
        } else {
            eventType = AuditEventType.PATIENT_CANCELLED_APPOINTMENT;
        }

        auditLogService.logEvent(
                eventType,
                actor.email(),
                role,
                "Appointment",
                savedAppointment.getId(),
                "Cancelled appointment ID " + savedAppointment.getId(),
                null
        );

        return savedAppointment;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment completeAppointment(
            Long appointmentId,
            LocalAccount actor,
            String role) {

        Appointment appointment = lockAppointmentSchedule(appointmentId);

        boolean admin = role.equals("ROLE_ADMIN");

        Long doctorUserId = appointment.getDoctor()
                .getUser()
                .getId();

        boolean doctorOwner = actor.id() == doctorUserId;

        if (!admin && !doctorOwner) {
            throw new ForbiddenException(
                    "Only the assigned doctor or admin can complete this appointment");
        }

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new BadRequestException(
                    "Only confirmed appointments can be completed");
        }

        // The doctor and appointment locks are already held, so the slot is last.
        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(
                        appointment.getSlot().getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Appointment slot not found"));

        if (slot.getStatus() != SlotStatus.BOOKED) {
            throw new BadRequestException("Appointment slot is not booked");
        }

        LocalDateTime slotEnd = LocalDateTime.of(slot.getDate(), slot.getEndTime());
        if (LocalDateTime.now().isBefore(slotEnd)) {
            throw new BadRequestException(
                    "Cannot complete an appointment before its scheduled end time");
        }

        appointment.setStatus(AppointmentStatus.COMPLETED);

        Appointment savedAppointment = appointmentRepository.save(appointment);

        AuditEventType eventType = admin
                ? AuditEventType.ADMIN_COMPLETED_APPOINTMENT
                : AuditEventType.DOCTOR_COMPLETED_APPOINTMENT;

        auditLogService.logEvent(
                eventType,
                actor.email(),
                role,
                "Appointment",
                savedAppointment.getId(),
                "Completed appointment ID " + savedAppointment.getId(),
                null
        );

        return savedAppointment;
    }

    private void lockDoctorSchedule(long doctorId) {
        doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
    }

    private Appointment lockAppointmentSchedule(Long appointmentId) {
        long doctorId = appointmentRepository.findDoctorIdById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
        lockDoctorSchedule(doctorId);
        return appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
    }
}
