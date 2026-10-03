package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.dto.DoctorAvailabilityResponse;
import com.harsh.hospitalmanagement.dto.DoctorResponse;
import com.harsh.hospitalmanagement.dto.SpecializationResponse;
import com.harsh.hospitalmanagement.dto.UserResponse;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.service.DoctorAvailabilityService;
import jakarta.validation.Valid;
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
    public ResponseEntity<DoctorAvailabilityResponse> createAvailability(
            @Valid @RequestBody DoctorAvailabilityRequest request) {

        DoctorAvailability availability =
                doctorAvailabilityService.createAvailability(request);

        return ResponseEntity.ok(toResponse(availability));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DoctorAvailabilityResponse> getAvailabilityById(
            @PathVariable Long id) {

        DoctorAvailability availability =
                doctorAvailabilityService.getAvailabilityById(id);

        return ResponseEntity.ok(toResponse(availability));
    }

    private DoctorAvailabilityResponse toResponse(
            DoctorAvailability availability) {

        Doctor doctor = availability.getDoctor();
        User user = doctor.getUser();
        Specialization specialization = doctor.getSpecialization();

        UserResponse userResponse = new UserResponse();
        userResponse.setId(user.getId());
        userResponse.setEmail(user.getEmail());
        userResponse.setRole(user.getRole());

        SpecializationResponse specializationResponse =
                new SpecializationResponse();
        specializationResponse.setId(specialization.getId());
        specializationResponse.setName(specialization.getName());
        specializationResponse.setDescription(
                specialization.getDescription());

        DoctorResponse doctorResponse = new DoctorResponse();
        doctorResponse.setId(doctor.getId());
        doctorResponse.setFirstName(doctor.getFirstName());
        doctorResponse.setLastName(doctor.getLastName());
        doctorResponse.setQualification(doctor.getQualification());
        doctorResponse.setExperience(doctor.getExperience());
        doctorResponse.setUser(userResponse);
        doctorResponse.setSpecialization(specializationResponse);

        DoctorAvailabilityResponse response =
                new DoctorAvailabilityResponse();

        response.setId(availability.getId());
        response.setDate(availability.getDate());
        response.setStartTime(availability.getStartTime());
        response.setEndTime(availability.getEndTime());
        response.setDoctor(doctorResponse);

        return response;
    }
}