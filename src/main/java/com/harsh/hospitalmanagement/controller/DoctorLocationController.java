package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorLocationRequest;
import com.harsh.hospitalmanagement.dto.DoctorLocationResponse;
import com.harsh.hospitalmanagement.dto.DoctorVideoFeeRequest;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.service.DoctorLocationService;
import com.harsh.hospitalmanagement.service.DoctorLocationService.ClinicDetails;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/doctors/{doctorId}")
public class DoctorLocationController {

    private final DoctorLocationService locationService;

    public DoctorLocationController(DoctorLocationService locationService) {
        this.locationService = locationService;
    }

    @PostMapping("/locations")
    public ResponseEntity<DoctorLocationResponse> create(
            @PathVariable long doctorId,
            @Valid @RequestBody DoctorLocationRequest request,
            Authentication authentication) {
        var clinic = locationService.create(positiveId(doctorId, "Doctor ID"), toDetails(request), authentication);
        var location = URI.create("/api/doctors/" + doctorId + "/locations/" + clinic.getId());
        return ResponseEntity.created(location).body(toResponse(clinic));
    }

    @GetMapping("/locations")
    public List<DoctorLocationResponse> listActive(@PathVariable long doctorId, Authentication authentication) {
        return locationService.listActive(positiveId(doctorId, "Doctor ID"), authentication).stream()
                .map(DoctorLocationController::toResponse)
                .toList();
    }

    @PutMapping("/locations/{locationId}")
    public DoctorLocationResponse update(
            @PathVariable long doctorId,
            @PathVariable long locationId,
            @Valid @RequestBody DoctorLocationRequest request,
            Authentication authentication) {
        return toResponse(locationService.update(positiveId(doctorId, "Doctor ID"),
                positiveId(locationId, "Clinic ID"), toDetails(request), authentication));
    }

    @DeleteMapping("/locations/{locationId}")
    public ResponseEntity<Void> archive(
            @PathVariable long doctorId, @PathVariable long locationId, Authentication authentication) {
        locationService.archive(positiveId(doctorId, "Doctor ID"), positiveId(locationId, "Clinic ID"), authentication);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/video-fee")
    public ResponseEntity<Void> setVideoFee(
            @PathVariable long doctorId,
            @Valid @RequestBody DoctorVideoFeeRequest request,
            Authentication authentication) {
        locationService.setVideoFee(positiveId(doctorId, "Doctor ID"), request.fee(), authentication);
        return ResponseEntity.noContent().build();
    }

    private static long positiveId(long id, String field) {
        if (id <= 0) {
            throw new BadRequestException(field + " must be a positive integer");
        }
        return id;
    }

    private static ClinicDetails toDetails(DoctorLocationRequest request) {
        return new ClinicDetails(request.clinicName(), request.addressLine1(), request.addressLine2(),
                request.locality(), request.city(), request.state(), request.postalCode(), request.contactPhone(),
                request.inClinicFee(), request.active());
    }

    private static DoctorLocationResponse toResponse(DoctorLocation clinic) {
        return new DoctorLocationResponse(clinic.getId(), clinic.getClinicName(), clinic.getAddressLine1(),
                clinic.getAddressLine2(), clinic.getLocality(), clinic.getCity(), clinic.getState(),
                clinic.getPostalCode(), clinic.getContactPhone(), clinic.getInClinicFee(), clinic.isActive());
    }
}
