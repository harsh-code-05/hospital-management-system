package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.*;
import com.harsh.hospitalmanagement.entity.DoctorApplication;
import com.harsh.hospitalmanagement.service.DoctorApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/doctor-applications")
public class DoctorApplicationController {

    private final DoctorApplicationService doctorApplicationService;

    public DoctorApplicationController(DoctorApplicationService doctorApplicationService) {
        this.doctorApplicationService = doctorApplicationService;
    }

    /**
     * Public: unauthenticated doctor registration.
     * Creates a User (PATIENT role) and a pending DoctorApplication.
     */
    @PostMapping("/register")
    public ResponseEntity<DoctorApplicationResponse> registerDoctor(
            @Valid @RequestBody DoctorRegisterRequest request) {

        DoctorApplication application =
                doctorApplicationService.registerDoctor(request);

        return ResponseEntity.ok(toResponse(application));
    }

    /**
     * Authenticated: existing PATIENT (e.g. Google OAuth user) submits a doctor application.
     */
    @PostMapping("/apply")
    public ResponseEntity<DoctorApplicationResponse> applyForDoctor(
            @Valid @RequestBody DoctorApplyRequest request,
            Authentication authentication) {

        DoctorApplication application =
                doctorApplicationService.applyForDoctor(
                        request,
                        authentication.getName()
                );

        return ResponseEntity.ok(toResponse(application));
    }

    /**
     * Authenticated: applicant checks their own application status.
     */
    @GetMapping("/me")
    public ResponseEntity<DoctorApplicationResponse> getMyApplication(
            Authentication authentication) {

        DoctorApplication application =
                doctorApplicationService.getMyApplication(authentication.getName());

        return ResponseEntity.ok(toResponse(application));
    }

    /**
     * ADMIN only: list all pending doctor applications.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<DoctorApplicationResponse>> getPendingApplications() {

        List<DoctorApplicationResponse> responses =
                doctorApplicationService.getPendingApplications()
                        .stream()
                        .map(this::toResponse)
                        .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * ADMIN only: view a specific application by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DoctorApplicationResponse> getApplicationById(
            @PathVariable Long id) {

        DoctorApplication application =
                doctorApplicationService.getApplicationById(id);

        return ResponseEntity.ok(toResponse(application));
    }

    /**
     * ADMIN only: approve a pending application.
     */
    @PatchMapping("/{id}/approve")
    public ResponseEntity<DoctorApplicationResponse> approveApplication(
            @PathVariable Long id,
            Authentication authentication) {

        DoctorApplication application =
                doctorApplicationService.approveApplication(
                        id,
                        authentication.getName()
                );

        return ResponseEntity.ok(toResponse(application));
    }

    /**
     * ADMIN only: reject a pending application with an optional reason.
     */
    @PatchMapping("/{id}/reject")
    public ResponseEntity<DoctorApplicationResponse> rejectApplication(
            @PathVariable Long id,
            @Valid @RequestBody RejectApplicationRequest request,
            Authentication authentication) {

        DoctorApplication application =
                doctorApplicationService.rejectApplication(
                        id,
                        request.getReason(),
                        authentication.getName()
                );

        return ResponseEntity.ok(toResponse(application));
    }

    // -----------------------------------------------------------------------
    // Mapping helper
    // -----------------------------------------------------------------------

    private DoctorApplicationResponse toResponse(DoctorApplication application) {

        UserResponse userResponse = new UserResponse();
        userResponse.setId(application.getUser().getId());
        userResponse.setEmail(application.getUser().getEmail());
        userResponse.setRole(application.getUser().getRole());

        SpecializationResponse specializationResponse = new SpecializationResponse();
        specializationResponse.setId(application.getSpecialization().getId());
        specializationResponse.setName(application.getSpecialization().getName());
        specializationResponse.setDescription(application.getSpecialization().getDescription());

        DoctorApplicationResponse response = new DoctorApplicationResponse();
        response.setId(application.getId());
        response.setStatus(application.getStatus());
        response.setRejectionReason(application.getRejectionReason());
        response.setCreatedAt(application.getCreatedAt());
        response.setReviewedAt(application.getReviewedAt());
        response.setFirstName(application.getFirstName());
        response.setLastName(application.getLastName());
        response.setQualification(application.getQualification());
        response.setExperience(application.getExperience());
        response.setUser(userResponse);
        response.setSpecialization(specializationResponse);

        return response;
    }
}
