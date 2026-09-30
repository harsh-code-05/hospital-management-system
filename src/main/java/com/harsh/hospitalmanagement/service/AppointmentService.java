package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentSlotRepository appointmentSlotRepository;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            AppointmentSlotRepository appointmentSlotRepository) {
        this.appointmentRepository = appointmentRepository;
        this.appointmentSlotRepository = appointmentSlotRepository;
    }

    public Appointment getAppointmentById(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));
    }

    @Transactional
    public Appointment bookAppointment(Appointment appointment) {

        AppointmentSlot slot = appointmentSlotRepository
                .findByIdForUpdate(appointment.getSlot().getId())
                .orElseThrow(() -> new RuntimeException("Appointment slot not found"));

        if (slot.getStatus() != com.harsh.hospitalmanagement.enums.SlotStatus.AVAILABLE) {
            throw new RuntimeException("Appointment slot is not available");
        }

        slot.setStatus(com.harsh.hospitalmanagement.enums.SlotStatus.BOOKED);

        appointment.setSlot(slot);

        return appointmentRepository.save(appointment);
    }
}