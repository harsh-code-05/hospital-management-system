package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AppointmentSlotService {

    private static final Duration SLOT_DURATION = Duration.ofMinutes(30);

    private final AppointmentSlotRepository appointmentSlotRepository;
    private final DoctorAvailabilityRepository doctorAvailabilityRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;

    public AppointmentSlotService(
            AppointmentSlotRepository appointmentSlotRepository,
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository,
            AppointmentRepository appointmentRepository) {

        this.appointmentSlotRepository = appointmentSlotRepository;
        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
    }

    public List<AppointmentSlot> getAvailableSlots(Long doctorId, LocalDate date) {
        if (date != null) {
            return appointmentSlotRepository.findBookableByDoctorAndDate(doctorId, date);
        }
        return appointmentSlotRepository.findBookableByDoctor(doctorId);
    }

    public AppointmentSlot getSlotById(Long id) {
        return appointmentSlotRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment slot not found"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<AppointmentSlot> generateSlots(
            Long availabilityId,
            LocalAccount actor,
            boolean admin) {

        long doctorId = doctorAvailabilityRepository.findDoctorIdById(availabilityId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor availability not found"));
        doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

        DoctorAvailability availability =
                doctorAvailabilityRepository.findById(availabilityId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Doctor availability not found"));
        if (availability.getDoctor().getId() != doctorId) {
            throw new ConflictException("Availability changed while acquiring the schedule lock. Retry the request.");
        }

        if (!admin) {
            long doctorUserId =
                    availability.getDoctor().getUser().getId();

            if (doctorUserId != actor.id()) {
                throw new ForbiddenException(
                        "You are not allowed to generate slots for this doctor's availability");
            }
        }

        LocalTime currentTime = availability.getStartTime();
        LocalTime endTime = availability.getEndTime();
        if (!currentTime.isBefore(endTime)) {
            throw new BadRequestException("Start time must be before end time");
        }

        long slotCount = Duration.between(currentTime, endTime).dividedBy(SLOT_DURATION);
        LocalDate date = availability.getDate();
        List<AppointmentSlot> slots = new ArrayList<>();

        for (long i = 0; i < slotCount; i++) {
            LocalTime slotEndTime = currentTime.plus(SLOT_DURATION);

            if (!appointmentRepository.existsOverlappingConfirmedAppointment(doctorId, date, currentTime, slotEndTime)
                    && !appointmentSlotRepository.existsOverlappingBookableSlot(doctorId, date, currentTime, slotEndTime)) {
                AppointmentSlot slot = appointmentSlotRepository.findByDoctor_IdAndDateAndStartTimeAndEndTime(
                        doctorId, date, currentTime, slotEndTime).orElse(null);
                if (slot == null) {
                    slot = new AppointmentSlot();
                    slot.setDate(date);
                    slot.setStartTime(currentTime);
                    slot.setEndTime(slotEndTime);
                    slot.setStatus(SlotStatus.AVAILABLE);
                    slot.setDoctor(availability.getDoctor());
                }
                // Reissue only an unoccupied, retired row with the exact same coordinates.
                if (slot.isRetired() && slot.getStatus() == SlotStatus.AVAILABLE) {
                    slot.setAvailability(availability);
                    slot.setAvailabilityRevision(availability.getRevision());
                    slot.setRetired(false);
                    slots.add(slot);
                }
            }

            currentTime = slotEndTime;
        }

        if (!slots.isEmpty()) {
            return appointmentSlotRepository.saveAll(slots);
        }

        return slots;
    }
}
