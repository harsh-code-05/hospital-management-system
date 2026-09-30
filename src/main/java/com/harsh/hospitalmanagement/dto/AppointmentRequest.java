package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotNull;

public class AppointmentRequest {

    @NotNull
    private Long patientId;

    @NotNull
    private Long doctorId;

    @NotNull
    private Long slotId;

    private String reason;

    public AppointmentRequest() {
    }

    public AppointmentRequest(Long patientId, Long doctorId, Long slotId, String reason) {
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.slotId = slotId;
        this.reason = reason;
    }

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public Long getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(Long doctorId) {
        this.doctorId = doctorId;
    }

    public Long getSlotId() {
        return slotId;
    }

    public void setSlotId(Long slotId) {
        this.slotId = slotId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}