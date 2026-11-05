package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.ConsultationMode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
public class DoctorAvailabilityResponse {

    private Long id;
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
    private Long locationId;
    private ConsultationMode consultationMode;

    private DoctorResponse doctor;
}
