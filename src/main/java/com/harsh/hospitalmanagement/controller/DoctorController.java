package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.service.DoctorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doctors")
public class DoctorController {

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    @PostMapping
    public ResponseEntity<Doctor> createDoctor(
            @RequestBody DoctorRequest request) {

        return ResponseEntity.ok(
                doctorService.createDoctor(request)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Doctor> getDoctor(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                doctorService.getDoctorById(id)
        );
    }
}