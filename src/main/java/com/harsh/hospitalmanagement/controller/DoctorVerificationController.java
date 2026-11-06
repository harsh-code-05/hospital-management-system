package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorVerificationCredentialsRequest;
import com.harsh.hospitalmanagement.dto.DoctorVerificationRejectionRequest;
import com.harsh.hospitalmanagement.dto.DoctorVerificationResponse;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.service.DoctorVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestController
@RequestMapping("/api/doctors/{doctorId}/verification")
public class DoctorVerificationController {

    private final DoctorVerificationService verificationService;

    public DoctorVerificationController(DoctorVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @GetMapping
    public DoctorVerificationResponse getVerification(@PathVariable long doctorId, Authentication authentication) {
        return verificationService.getVerification(positiveId(doctorId), authentication);
    }

    @PostMapping("/credentials")
    public ResponseEntity<Void> updateCredentials(
            @PathVariable long doctorId,
            @Valid @RequestBody DoctorVerificationCredentialsRequest request,
            Authentication authentication) {
        verificationService.updateRegistrationDetails(positiveId(doctorId), request.registrationNumber(),
                request.medicalCouncil(), request.registrationYear(), authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/submit")
    public ResponseEntity<Void> submit(@PathVariable long doctorId, Authentication authentication) {
        return transition(doctorId, VerificationStatus.SUBMITTED, null, authentication);
    }

    @PostMapping("/review")
    public ResponseEntity<Void> review(@PathVariable long doctorId, Authentication authentication) {
        return transition(doctorId, VerificationStatus.UNDER_REVIEW, null, authentication);
    }

    @PostMapping("/approve")
    public ResponseEntity<Void> approve(@PathVariable long doctorId, Authentication authentication) {
        return transition(doctorId, VerificationStatus.VERIFIED, null, authentication);
    }

    @PostMapping("/reject")
    public ResponseEntity<Void> reject(
            @PathVariable long doctorId,
            @Valid @RequestBody DoctorVerificationRejectionRequest request,
            Authentication authentication) {
        return transition(doctorId, VerificationStatus.REJECTED, request.reason().strip(), authentication);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", "Invalid verification request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> invalidDoctorId() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", "Doctor ID must be a positive integer"));
    }

    private ResponseEntity<Void> transition(long doctorId, VerificationStatus next, String notes,
                                            Authentication authentication) {
        verificationService.transition(positiveId(doctorId), next, notes, authentication);
        return ResponseEntity.noContent().build();
    }

    private static long positiveId(long doctorId) {
        if (doctorId <= 0) {
            throw new BadRequestException("Doctor ID must be a positive integer");
        }
        return doctorId;
    }
}
