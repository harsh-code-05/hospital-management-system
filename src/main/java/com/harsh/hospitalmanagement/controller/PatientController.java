package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.PatientRequest;
import com.harsh.hospitalmanagement.dto.PatientResponse;
import com.harsh.hospitalmanagement.dto.UserResponse;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.service.PatientService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping
    public ResponseEntity<PatientResponse> createPatient(
            @Valid @RequestBody PatientRequest request) {

        Patient patient = patientService.createPatient(request);

        return ResponseEntity.ok(toResponse(patient));
    }

    @GetMapping("/me")
    public ResponseEntity<PatientResponse> getMyPatientProfile(
            Authentication authentication) {

        Patient patient = patientService.getPatientByEmail(authentication.getName());

        return ResponseEntity.ok(toResponse(patient));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PatientResponse> getPatientById(
            @PathVariable Long id,
            Authentication authentication) {

        boolean admin = authentication.getAuthorities()
                .stream()
                .anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_ADMIN"));

        Patient patient = patientService.getPatientByIdForUser(
                id,
                authentication.getName(),
                admin
        );

        return ResponseEntity.ok(toResponse(patient));
    }

    private PatientResponse toResponse(Patient patient) {

        User user = patient.getUser();

        UserResponse userResponse = new UserResponse();
        userResponse.setId(user.getId());
        userResponse.setEmail(user.getEmail());
        userResponse.setRole(user.getRole());

        PatientResponse response = new PatientResponse();

        response.setId(patient.getId());
        response.setFirstName(patient.getFirstName());
        response.setLastName(patient.getLastName());
        response.setDateOfBirth(patient.getDateOfBirth());
        response.setGender(patient.getGender());
        response.setPhone(patient.getPhone());
        response.setUser(userResponse);

        return response;
    }
}