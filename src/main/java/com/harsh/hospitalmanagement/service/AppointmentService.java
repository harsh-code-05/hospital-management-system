package com.harsh.hospitalmanagement.service;

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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentSlotRepository appointmentSlotRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            AppointmentSlotRepository appointmentSlotRepository,
            PatientRepository patientRepository,
            DoctorRepository doctorRepository) {

        this.appointmentRepository = appointmentRepository;
        this.appointmentSlotRepository = appointmentSlotRepository;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
    }

    public Appointment getAppointmentById(Long id) {

        return appointmentRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment not found"));
    }

    @Transactional
    public Appointment bookAppointment(
            Long patientId,
            Long doctorId,
            Long slotId,
            String reason,
            String authenticatedEmail) {

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Patient not found"));

        String patientEmail = patient.getUser().getEmail();

        if (!patientEmail.equals(authenticatedEmail)) {
            throw new ForbiddenException(
                    "You are not allowed to book an appointment for this patient");
        }

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Doctor not found"));


        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Appointment slot not found"));

        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            throw new BadRequestException(
                    "Appointment slot is not available");
        }

        if (!slot.getDoctor().getId().equals(doctor.getId())) {
            throw new BadRequestException(
                    "Slot does not belong to this doctor");
        }

        slot.setStatus(SlotStatus.BOOKED);

        Appointment appointment = new Appointment();

        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setSlot(slot);
        appointment.setReason(reason);
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setCreatedAt(LocalDateTime.now());

        return appointmentRepository.save(appointment);
    }
    @Transactional
    public Appointment cancelAppointment(
            Long appointmentId,
            String authenticatedEmail,
            String role) {

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment not found"));

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new BadRequestException(
                    "Only confirmed appointments can be cancelled");
        }

        boolean admin = role.equals("ROLE_ADMIN");

        String patientEmail = appointment.getPatient()
                .getUser()
                .getEmail();

        String doctorEmail = appointment.getDoctor()
                .getUser()
                .getEmail();

        boolean patientOwner = patientEmail.equals(authenticatedEmail);
        boolean doctorOwner = doctorEmail.equals(authenticatedEmail);

        if (!admin && !patientOwner && !doctorOwner) {
            throw new ForbiddenException(
                    "You are not allowed to cancel this appointment");
        }

        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(
                        appointment.getSlot().getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Appointment slot not found"));

        appointment.setStatus(AppointmentStatus.CANCELLED);
        slot.setStatus(SlotStatus.AVAILABLE);

        return appointmentRepository.save(appointment);
    }

    @Transactional
    public Appointment completeAppointment(
            Long appointmentId,
            String authenticatedEmail,
            String role) {

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment not found"));

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new BadRequestException(
                    "Only confirmed appointments can be completed");
        }

        boolean admin = role.equals("ROLE_ADMIN");

        String doctorEmail = appointment.getDoctor()
                .getUser()
                .getEmail();

        boolean doctorOwner = doctorEmail.equals(authenticatedEmail);

        if (!admin && !doctorOwner) {
            throw new ForbiddenException(
                    "Only the assigned doctor or admin can complete this appointment");
        }

        appointment.setStatus(AppointmentStatus.COMPLETED);

        return appointmentRepository.save(appointment);
    }
}