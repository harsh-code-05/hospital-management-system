package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import org.springframework.stereotype.Service;

@Service
public class AppointmentSlotService {

    private final AppointmentSlotRepository appointmentSlotRepository;

    public AppointmentSlotService(AppointmentSlotRepository appointmentSlotRepository) {
        this.appointmentSlotRepository = appointmentSlotRepository;
    }

    public AppointmentSlot getSlotById(Long id) {
        return appointmentSlotRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Appointment slot not found"));
    }

    public AppointmentSlot saveSlot(AppointmentSlot slot) {
        return appointmentSlotRepository.save(slot);
    }
}