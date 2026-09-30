package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.service.DoctorAvailabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doctor-availability")
public class DoctorAvailabilityController {

    private final DoctorAvailabilityService doctorAvailabilityService;

    public DoctorAvailabilityController(
            DoctorAvailabilityService doctorAvailabilityService) {

        this.doctorAvailabilityService = doctorAvailabilityService;
    }

    @PostMapping
    public ResponseEntity<DoctorAvailability> createAvailability(
            @RequestBody DoctorAvailabilityRequest request) {

        return ResponseEntity.ok(
                doctorAvailabilityService.createAvailability(request)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<DoctorAvailability> getAvailability(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                doctorAvailabilityService.getAvailabilityById(id)
        );
    }
}