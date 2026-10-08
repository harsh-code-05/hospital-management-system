package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import org.springframework.stereotype.Service;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;

import java.time.LocalDate;

import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.service.AuditLogService;

@Service
public class DoctorAvailabilityService {

    private final DoctorAvailabilityRepository doctorAvailabilityRepository;
    private final DoctorRepository doctorRepository;
    private final AuditLogService auditLogService;

    public DoctorAvailabilityService(
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository,
            AuditLogService auditLogService) {

        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
        this.doctorRepository = doctorRepository;
        this.auditLogService = auditLogService;
    }

    public DoctorAvailability getAvailabilityById(Long id) {
        return doctorAvailabilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor availability not found"));
    }

    public DoctorAvailability saveAvailability(DoctorAvailability availability) {
        return doctorAvailabilityRepository.save(availability);
    }

    public DoctorAvailability createAvailability(
            DoctorAvailabilityRequest request,
            String authenticatedEmail,
            boolean admin) {
        Doctor doctor = doctorRepository.findById(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

        if (!admin) {
            String doctorEmail = doctor.getUser().getEmail();

            if (!doctorEmail.equals(authenticatedEmail)) {
                throw new ForbiddenException(
                        "You are not allowed to manage this doctor's availability");
            }
        }

        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new BadRequestException(
                    "Start time must be before end time"
            );
        }
        if (request.getDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("Availability date cannot be in the past");
        }


        boolean overlapExists =
                doctorAvailabilityRepository.existsOverlappingAvailability(
                        request.getDoctorId(),
                        request.getDate(),
                        request.getStartTime(),
                        request.getEndTime()
                );

        if (overlapExists) {
            throw new BadRequestException(
                    "Doctor already has availability during this time");
        }


        DoctorAvailability availability = new DoctorAvailability();

        availability.setDoctor(doctor);
        availability.setDate(request.getDate());
        availability.setStartTime(request.getStartTime());
        availability.setEndTime(request.getEndTime());

        DoctorAvailability savedAvailability = doctorAvailabilityRepository.save(availability);

        auditLogService.logEvent(
                AuditEventType.DOCTOR_CREATED_AVAILABILITY,
                authenticatedEmail,
                admin ? "ROLE_ADMIN" : "ROLE_DOCTOR",
                "DoctorAvailability",
                savedAvailability.getId(),
                "Created availability for doctor ID " + doctor.getId() + " on " + savedAvailability.getDate(),
                null
        );

        return savedAvailability;
    }

    public DoctorAvailability updateAvailability(
            Long id,
            DoctorAvailabilityRequest request,
            String authenticatedEmail,
            boolean admin) {

        DoctorAvailability availability = doctorAvailabilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor availability not found"));

        Doctor doctor = availability.getDoctor();

        if (!admin) {
            String doctorEmail = doctor.getUser().getEmail();

            if (!doctorEmail.equals(authenticatedEmail)) {
                throw new ForbiddenException(
                        "You are not allowed to manage this doctor's availability");
            }

            if (!request.getDoctorId().equals(doctor.getId())) {
                throw new ForbiddenException(
                        "You cannot reassign availability to another doctor");
            }
        } else {
            if (!request.getDoctorId().equals(doctor.getId())) {
                doctor = doctorRepository.findById(request.getDoctorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
                availability.setDoctor(doctor);
            }
        }

        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new BadRequestException(
                    "Start time must be before end time"
            );
        }
        if (request.getDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("Availability date cannot be in the past");
        }

        boolean overlapExists =
                doctorAvailabilityRepository.existsOverlappingAvailabilityExcludingId(
                        doctor.getId(),
                        request.getDate(),
                        request.getStartTime(),
                        request.getEndTime(),
                        id
                );

        if (overlapExists) {
            throw new BadRequestException(
                    "Doctor already has availability during this time");
        }

        availability.setDate(request.getDate());
        availability.setStartTime(request.getStartTime());
        availability.setEndTime(request.getEndTime());

        DoctorAvailability savedAvailability = doctorAvailabilityRepository.save(availability);

        auditLogService.logEvent(
                AuditEventType.DOCTOR_UPDATED_AVAILABILITY,
                authenticatedEmail,
                admin ? "ROLE_ADMIN" : "ROLE_DOCTOR",
                "DoctorAvailability",
                savedAvailability.getId(),
                "Updated availability ID " + savedAvailability.getId() + " for doctor ID " + doctor.getId(),
                null
        );

        return savedAvailability;
    }
}