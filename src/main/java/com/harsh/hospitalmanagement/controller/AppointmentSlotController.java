package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/appointment-slots")
public class AppointmentSlotController {

    private final AppointmentSlotService appointmentSlotService;

    public AppointmentSlotController(
            AppointmentSlotService appointmentSlotService) {

        this.appointmentSlotService = appointmentSlotService;
    }

    @PostMapping("/generate/{availabilityId}")
    public ResponseEntity<List<AppointmentSlot>> generateSlots(
            @PathVariable Long availabilityId) {

        return ResponseEntity.ok(
                appointmentSlotService.generateSlots(availabilityId)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentSlot> getSlot(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                appointmentSlotService.getSlotById(id)
        );
    }
}