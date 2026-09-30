package com.harsh.hospitalmanagement.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public class DoctorAvailabilityRequest {

    private Long doctorId;
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;

    public DoctorAvailabilityRequest() {
    }

    public DoctorAvailabilityRequest(Long doctorId, LocalDate date,
                                     LocalTime startTime, LocalTime endTime) {
        this.doctorId = doctorId;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public Long getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(Long doctorId) {
        this.doctorId = doctorId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }
}