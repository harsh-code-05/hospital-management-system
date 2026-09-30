package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.PatientRequest;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.service.PatientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping
    public ResponseEntity<Patient> createPatient(
            @RequestBody PatientRequest request) {

        return ResponseEntity.ok(
                patientService.createPatient(request)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Patient> getPatient(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                patientService.getPatientById(id)
        );
    }
}