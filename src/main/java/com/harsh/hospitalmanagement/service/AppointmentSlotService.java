package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import org.springframework.stereotype.Service;

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

    public AppointmentSlotService(
            AppointmentSlotRepository appointmentSlotRepository,
            DoctorAvailabilityRepository doctorAvailabilityRepository) {

        this.appointmentSlotRepository = appointmentSlotRepository;
        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
    }

    public List<AppointmentSlot> getAvailableSlots(Long doctorId, LocalDate date) {
        if (date != null) {
            return appointmentSlotRepository.findByDoctor_IdAndDateAndStatus(
                    doctorId, date, SlotStatus.AVAILABLE);
        }
        return appointmentSlotRepository.findByDoctor_IdAndStatus(
                doctorId, SlotStatus.AVAILABLE);
    }

    public AppointmentSlot getSlotById(Long id) {
        return appointmentSlotRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment slot not found"));
    }

    public AppointmentSlot saveSlot(AppointmentSlot slot) {
        return appointmentSlotRepository.save(slot);
    }

    public List<AppointmentSlot> generateSlots(
            Long availabilityId,
            LocalAccount actor,
            boolean admin) {

        DoctorAvailability availability =
                doctorAvailabilityRepository.findById(availabilityId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Doctor availability not found"));

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
        List<AppointmentSlot> slots = new ArrayList<>();

        for (long i = 0; i < slotCount; i++) {
            LocalTime slotEndTime = currentTime.plus(SLOT_DURATION);

            boolean alreadyExists =
                    appointmentSlotRepository.existsByDoctorAndDateAndTime(
                            availability.getDoctor().getId(),
                            availability.getDate(),
                            currentTime,
                            slotEndTime
                    );

            if (!alreadyExists) {

                AppointmentSlot slot = new AppointmentSlot();

                slot.setDate(availability.getDate());
                slot.setStartTime(currentTime);
                slot.setEndTime(slotEndTime);
                slot.setStatus(SlotStatus.AVAILABLE);
                slot.setDoctor(availability.getDoctor());

                slots.add(slot);
            }

            currentTime = slotEndTime;
        }

        if (!slots.isEmpty()) {
            return appointmentSlotRepository.saveAll(slots);
        }

        return slots;
    }
}
