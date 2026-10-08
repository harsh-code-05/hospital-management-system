package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.dto.DoctorResponse;
import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.dto.SpecializationResponse;
import com.harsh.hospitalmanagement.dto.UserResponse;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.service.DoctorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doctors")
public class DoctorController {

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    @PostMapping
    public ResponseEntity<DoctorResponse> createDoctor(
            @Valid @RequestBody DoctorRequest request,
            Authentication authentication) {

        String adminEmail = authentication != null ? authentication.getName() : "admin";
        Doctor doctor = doctorService.createDoctor(request, adminEmail);

        return ResponseEntity.ok(toResponse(doctor));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DoctorResponse> updateDoctor(
            @PathVariable Long id,
            @Valid @RequestBody DoctorUpdateRequest request,
            Authentication authentication) {

        String adminEmail = authentication != null ? authentication.getName() : "admin";
        Doctor doctor = doctorService.updateDoctor(id, request, adminEmail);

        return ResponseEntity.ok(toResponse(doctor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DoctorResponse> getDoctorById(
            @PathVariable Long id) {

        Doctor doctor = doctorService.getDoctorById(id);

        return ResponseEntity.ok(toResponse(doctor));
    }

    private DoctorResponse toResponse(Doctor doctor) {

        User user = doctor.getUser();

        UserResponse userResponse = new UserResponse();
        userResponse.setId(user.getId());
        userResponse.setEmail(user.getEmail());
        userResponse.setRole(user.getRole());

        Specialization specialization = doctor.getSpecialization();

        SpecializationResponse specializationResponse =
                new SpecializationResponse();

        specializationResponse.setId(specialization.getId());
        specializationResponse.setName(specialization.getName());
        specializationResponse.setDescription(specialization.getDescription());

        DoctorResponse response = new DoctorResponse();

        response.setId(doctor.getId());
        response.setFirstName(doctor.getFirstName());
        response.setLastName(doctor.getLastName());
        response.setQualification(doctor.getQualification());
        response.setExperience(doctor.getExperience());
        response.setUser(userResponse);
        response.setSpecialization(specializationResponse);

        return response;
    }
}