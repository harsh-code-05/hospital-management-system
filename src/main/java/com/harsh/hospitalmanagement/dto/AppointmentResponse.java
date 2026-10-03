package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AppointmentResponse {

    private Long id;
    private String reason;
    private LocalDateTime createdAt;
    private AppointmentStatus status;

    private PatientResponse patient;
    private DoctorResponse doctor;
    private AppointmentSlotResponse slot;
}