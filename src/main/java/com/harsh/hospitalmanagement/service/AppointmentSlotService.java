package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.enums.SlotStatus;
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
                .orElseThrow(() -> new RuntimeException("Appointment slot not found"));
    }

    public AppointmentSlot saveSlot(AppointmentSlot slot) {
        return appointmentSlotRepository.save(slot);
    }

    public List<AppointmentSlot> generateSlots(Long availabilityId) {

        DoctorAvailability availability =
                doctorAvailabilityRepository.findById(availabilityId)
                        .orElseThrow(() ->
                                new RuntimeException("Doctor availability not found"));

        LocalTime currentTime = availability.getStartTime();
        LocalTime endTime = availability.getEndTime();

        List<AppointmentSlot> slots = new ArrayList<>();

        while (currentTime.plusMinutes(30).compareTo(endTime) <= 0) {

            AppointmentSlot slot = new AppointmentSlot();

            slot.setDate(availability.getDate());
            slot.setStartTime(currentTime);
            slot.setEndTime(currentTime.plusMinutes(30));
            slot.setStatus(SlotStatus.AVAILABLE);
            slot.setDoctor(availability.getDoctor());

            slots.add(slot);

            currentTime = currentTime.plusMinutes(30);
        }

        return appointmentSlotRepository.saveAll(slots);
    }
}