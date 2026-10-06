package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AppointmentSlotService {

    private final AppointmentSlotRepository appointmentSlotRepository;
    private final DoctorAvailabilityRepository doctorAvailabilityRepository;

    public AppointmentSlotService(
            AppointmentSlotRepository appointmentSlotRepository,
            DoctorAvailabilityRepository doctorAvailabilityRepository) {

        this.appointmentSlotRepository = appointmentSlotRepository;
        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
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
            String authenticatedEmail,
            boolean admin) {

        DoctorAvailability availability =
                doctorAvailabilityRepository.findById(availabilityId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Doctor availability not found"));

        if (!admin) {
            String doctorEmail =
                    availability.getDoctor().getUser().getEmail();

            if (!doctorEmail.equals(authenticatedEmail)) {
                throw new ForbiddenException(
                        "You are not allowed to generate slots for this doctor's availability");
            }
        }

        LocalTime currentTime = availability.getStartTime();
        LocalTime endTime = availability.getEndTime();

        List<AppointmentSlot> slots = new ArrayList<>();

        while (currentTime.plusMinutes(30).compareTo(endTime) <= 0) {

            LocalTime slotEndTime = currentTime.plusMinutes(30);

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