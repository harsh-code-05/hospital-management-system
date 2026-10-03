package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.SlotStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
public class AppointmentSlotResponse {

    private Long id;
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
    private SlotStatus status;

    private DoctorResponse doctor;
}