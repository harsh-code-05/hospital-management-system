package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.AppointmentRequest;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.service.AppointmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public ResponseEntity<Appointment> bookAppointment(
            @RequestBody AppointmentRequest request) {

        Appointment appointment = appointmentService.bookAppointment(
                request.getPatientId(),
                request.getDoctorId(),
                request.getSlotId(),
                request.getReason()
        );

        return ResponseEntity.ok(appointment);
    }
}