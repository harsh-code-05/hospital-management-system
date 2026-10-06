package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.*;
import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.service.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(
            @Valid @RequestBody AppointmentRequest request,
            Authentication authentication) {

        Appointment appointment = appointmentService.bookAppointment(
                request.getPatientId(),
                request.getDoctorId(),
                request.getSlotId(),
                request.getReason(),
                authentication.getName()
        );

        return ResponseEntity.ok(toResponse(appointment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> getAppointmentById(
            @PathVariable Long id) {

        Appointment appointment =
                appointmentService.getAppointmentById(id);

        return ResponseEntity.ok(toResponse(appointment));
    }

    private AppointmentResponse toResponse(Appointment appointment) {

        Patient patient = appointment.getPatient();
        User patientUser = patient.getUser();

        UserResponse patientUserResponse = new UserResponse();
        patientUserResponse.setId(patientUser.getId());
        patientUserResponse.setEmail(patientUser.getEmail());
        patientUserResponse.setRole(patientUser.getRole());

        PatientResponse patientResponse = new PatientResponse();
        patientResponse.setId(patient.getId());
        patientResponse.setFirstName(patient.getFirstName());
        patientResponse.setLastName(patient.getLastName());
        patientResponse.setDateOfBirth(patient.getDateOfBirth());
        patientResponse.setGender(patient.getGender());
        patientResponse.setPhone(patient.getPhone());
        patientResponse.setUser(patientUserResponse);


        Doctor doctor = appointment.getDoctor();
        User doctorUser = doctor.getUser();
        Specialization specialization = doctor.getSpecialization();

        UserResponse doctorUserResponse = new UserResponse();
        doctorUserResponse.setId(doctorUser.getId());
        doctorUserResponse.setEmail(doctorUser.getEmail());
        doctorUserResponse.setRole(doctorUser.getRole());

        SpecializationResponse specializationResponse =
                new SpecializationResponse();
        specializationResponse.setId(specialization.getId());
        specializationResponse.setName(specialization.getName());
        specializationResponse.setDescription(
                specialization.getDescription()
        );

        DoctorResponse doctorResponse = new DoctorResponse();
        doctorResponse.setId(doctor.getId());
        doctorResponse.setFirstName(doctor.getFirstName());
        doctorResponse.setLastName(doctor.getLastName());
        doctorResponse.setQualification(doctor.getQualification());
        doctorResponse.setExperience(doctor.getExperience());
        doctorResponse.setUser(doctorUserResponse);
        doctorResponse.setSpecialization(specializationResponse);


        AppointmentSlot slot = appointment.getSlot();

        AppointmentSlotResponse slotResponse =
                new AppointmentSlotResponse();

        slotResponse.setId(slot.getId());
        slotResponse.setDate(slot.getDate());
        slotResponse.setStartTime(slot.getStartTime());
        slotResponse.setEndTime(slot.getEndTime());
        slotResponse.setStatus(slot.getStatus());
        slotResponse.setDoctor(doctorResponse);


        AppointmentResponse response = new AppointmentResponse();

        response.setId(appointment.getId());
        response.setReason(appointment.getReason());
        response.setCreatedAt(appointment.getCreatedAt());
        response.setStatus(appointment.getStatus());
        response.setPatient(patientResponse);
        response.setDoctor(doctorResponse);
        response.setSlot(slotResponse);

        return response;
    }
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(
            @PathVariable Long id,
            Authentication authentication) {

        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(authority -> authority.getAuthority())
                .orElse("");

        Appointment appointment = appointmentService.cancelAppointment(
                id,
                authentication.getName(),
                role
        );

        return ResponseEntity.ok(toResponse(appointment));
    }
    @PatchMapping("/{id}/complete")
    public ResponseEntity<AppointmentResponse> completeAppointment(
            @PathVariable Long id,
            Authentication authentication) {

        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(authority -> authority.getAuthority())
                .orElse("");

        Appointment appointment = appointmentService.completeAppointment(
                id,
                authentication.getName(),
                role
        );

        return ResponseEntity.ok(toResponse(appointment));
    }
}