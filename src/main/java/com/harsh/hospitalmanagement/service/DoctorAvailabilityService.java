package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorLocationRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;

import java.time.LocalDate;
import java.util.Objects;

import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.service.AuditLogService;

@Service
public class DoctorAvailabilityService {

    private final DoctorAvailabilityRepository doctorAvailabilityRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentSlotRepository appointmentSlotRepository;
    private final AppointmentRepository appointmentRepository;
    private final DoctorLocationRepository doctorLocationRepository;
    private final AuditLogService auditLogService;

    public DoctorAvailabilityService(
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository,
            AppointmentSlotRepository appointmentSlotRepository,
            AppointmentRepository appointmentRepository,
            DoctorLocationRepository doctorLocationRepository,
            AuditLogService auditLogService) {

        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentSlotRepository = appointmentSlotRepository;
        this.appointmentRepository = appointmentRepository;
        this.doctorLocationRepository = doctorLocationRepository;
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

        ConsultationTerms terms = resolveTerms(doctor, request, null);
        rejectConflictingAppointments(doctor, request, terms);
        DoctorAvailability availability = new DoctorAvailability();

        availability.setDoctor(doctor);
        availability.setDate(request.getDate());
        availability.setStartTime(request.getStartTime());
        availability.setEndTime(request.getEndTime());
        applyTerms(availability, terms);

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

        var linkedBookings = appointmentRepository.findConfirmedByAvailabilityId(id);
        if (currentDoctorId != targetDoctorId && !linkedBookings.isEmpty()) {
            throw new ConflictException("Cannot reassign availability with confirmed appointments");
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

        ConsultationTerms terms = resolveTerms(target, request, availability);
        // The original provenance includes bookings outside the proposed interval and on older revisions.
        for (Appointment booking : linkedBookings) {
            requireContainedBooking(booking, target, request, terms);
        }
        rejectConflictingAppointments(target, request, terms);
        if (currentDoctorId != targetDoctorId
                || !request.getDate().equals(availability.getDate())
                || !request.getStartTime().equals(availability.getStartTime())
                || !request.getEndTime().equals(availability.getEndTime())
                || availability.getConsultationMode() != availabilityMode(terms)
                || !Objects.equals(locationId(availability.getLocation()), locationId(terms.location()))) {
            appointmentSlotRepository.findUnretiredForRevision(id, availability.getRevision())
                    .forEach(slot -> slot.setRetired(true));
            availability.setRevision(availability.getRevision() + 1);
            availability.setDoctor(target);
            availability.setDate(request.getDate());
            availability.setStartTime(request.getStartTime());
            availability.setEndTime(request.getEndTime());
            applyTerms(availability, terms);
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

    private ConsultationTerms resolveTerms(Doctor doctor, DoctorAvailabilityRequest request,
                                           DoctorAvailability existing) {
        Long locationId = request.getLocationId();
        ConsultationMode mode = request.getConsultationMode();
        if (mode == null && locationId == null) {
            // Legacy updates preserve the existing classification.
            return existing == null ? ConsultationTerms.forSchedule(doctor, null, null)
                    : ConsultationTerms.forSchedule(doctor, existing.getConsultationMode(), existing.getLocation());
        }
        if (mode == ConsultationMode.IN_CLINIC && locationId == null) {
            throw new BadRequestException("In-clinic availability requires a clinic");
        }
        DoctorLocation location = locationId == null ? null
                : doctorLocationRepository.findByIdAndDoctor_IdAndActiveTrueAndArchivedFalse(locationId, doctor.getId())
                .orElseThrow(() -> new BadRequestException("An active clinic owned by the doctor is required"));
        return ConsultationTerms.forSchedule(doctor, mode, location);
    }

    private void rejectConflictingAppointments(Doctor doctor, DoctorAvailabilityRequest request, ConsultationTerms terms) {
        for (Appointment appointment : appointmentRepository.findOverlappingConfirmedAppointments(
                doctor.getId(), request.getDate(), request.getStartTime(), request.getEndTime())) {
            if (terms.mode() != null || !isLegacyBooking(appointment)) {
                requireContainedBooking(appointment, doctor, request, terms);
            }
        }
    }

    private void requireContainedBooking(Appointment booking, Doctor doctor, DoctorAvailabilityRequest request,
                                         ConsultationTerms terms) {
        if (terms.mode() != null && isLegacyBooking(booking)) {
            throw new ConflictException("Legacy confirmed appointments must be reconciled before assigning a clinic or video mode");
        }

        var slot = booking.getSlot();
        if (!Objects.equals(booking.getDoctor().getId(), doctor.getId())
                || booking.getConsultationMode() != terms.mode()
                || !Objects.equals(locationId(booking.getLocation()), locationId(terms.location()))
                || !Objects.equals(slot.getDate(), request.getDate())
                || slot.getStartTime() == null || slot.getEndTime() == null
                || slot.getStartTime().isBefore(request.getStartTime())
                || slot.getEndTime().isAfter(request.getEndTime())) {
            throw new ConflictException("Availability must contain each confirmed appointment with its original doctor, mode and location");
        }
    }

    private boolean isLegacyBooking(Appointment booking) {
        return booking.getConsultationMode() == null && booking.getLocation() == null;
    }

    private void applyTerms(DoctorAvailability availability, ConsultationTerms terms) {
        availability.setLocation(terms.location());
        availability.setConsultationMode(availabilityMode(terms));
    }

    private ConsultationMode availabilityMode(ConsultationTerms terms) {
        return terms.mode() == null ? ConsultationMode.IN_CLINIC : terms.mode();
    }

    private Long locationId(DoctorLocation location) {
        return location == null ? null : location.getId();
    }

    private Doctor lockDoctor(long doctorId) {
        return doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
    }
}
