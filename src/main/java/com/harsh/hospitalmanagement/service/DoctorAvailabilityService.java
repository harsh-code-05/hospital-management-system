package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;

import java.time.LocalDate;

import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.service.AuditLogService;

@Service
public class DoctorAvailabilityService {

    private final DoctorAvailabilityRepository doctorAvailabilityRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentSlotRepository appointmentSlotRepository;
    private final AuditLogService auditLogService;

    public DoctorAvailabilityService(
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository,
            AppointmentSlotRepository appointmentSlotRepository,
            AuditLogService auditLogService) {

        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentSlotRepository = appointmentSlotRepository;
        this.auditLogService = auditLogService;
    }

    public DoctorAvailability getAvailabilityById(Long id) {
        return doctorAvailabilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor availability not found"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DoctorAvailability createAvailability(
            DoctorAvailabilityRequest request,
            LocalAccount actor,
            boolean admin) {
        Doctor doctor = lockDoctor(request.getDoctorId());

        if (!admin) {
            long doctorUserId = doctor.getUser().getId();

            if (doctorUserId != actor.id()) {
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
                actor.email(),
                admin ? "ROLE_ADMIN" : "ROLE_DOCTOR",
                "DoctorAvailability",
                savedAvailability.getId(),
                "Created availability for doctor ID " + doctor.getId() + " on " + savedAvailability.getDate(),
                null
        );

        return savedAvailability;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DoctorAvailability updateAvailability(
            Long id,
            DoctorAvailabilityRequest request,
            LocalAccount actor,
            boolean admin) {

        long currentDoctorId = doctorAvailabilityRepository.findDoctorIdById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor availability not found"));
        long targetDoctorId = admin ? request.getDoctorId() : currentDoctorId;
        // Reassignment takes both schedule locks in ascending ID order.
        Doctor target = lockDoctor(Math.min(currentDoctorId, targetDoctorId));
        if (currentDoctorId < targetDoctorId) {
            target = lockDoctor(targetDoctorId);
        } else if (currentDoctorId > targetDoctorId) {
            lockDoctor(currentDoctorId);
        }

        DoctorAvailability availability = getAvailabilityById(id);
        Doctor doctor = availability.getDoctor();
        if (doctor.getId() != currentDoctorId) {
            throw new ConflictException("Availability changed while acquiring the schedule lock. Retry the request.");
        }

        if (!admin) {
            long doctorUserId = doctor.getUser().getId();

            if (doctorUserId != actor.id()) {
                throw new ForbiddenException(
                        "You are not allowed to manage this doctor's availability");
            }

            if (!request.getDoctorId().equals(doctor.getId())) {
                throw new ForbiddenException(
                        "You cannot reassign availability to another doctor");
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
                        targetDoctorId,
                        request.getDate(),
                        request.getStartTime(),
                        request.getEndTime(),
                        id
                );

        if (overlapExists) {
            throw new BadRequestException(
                    "Doctor already has availability during this time");
        }

        if (currentDoctorId != targetDoctorId
                || !request.getDate().equals(availability.getDate())
                || !request.getStartTime().equals(availability.getStartTime())
                || !request.getEndTime().equals(availability.getEndTime())) {
            appointmentSlotRepository.findUnretiredForRevision(id, availability.getRevision())
                    .forEach(slot -> slot.setRetired(true));
            availability.setRevision(availability.getRevision() + 1);
            availability.setDoctor(target);
            availability.setDate(request.getDate());
            availability.setStartTime(request.getStartTime());
            availability.setEndTime(request.getEndTime());
        }

        DoctorAvailability savedAvailability = doctorAvailabilityRepository.save(availability);

        auditLogService.logEvent(
                AuditEventType.DOCTOR_UPDATED_AVAILABILITY,
                actor.email(),
                admin ? "ROLE_ADMIN" : "ROLE_DOCTOR",
                "DoctorAvailability",
                savedAvailability.getId(),
                "Updated availability ID " + savedAvailability.getId() + " for doctor ID " + targetDoctorId,
                null
        );

        return savedAvailability;
    }

    private Doctor lockDoctor(long doctorId) {
        return doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
    }
}
