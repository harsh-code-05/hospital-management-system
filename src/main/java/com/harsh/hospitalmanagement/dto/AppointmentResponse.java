package com.harsh.hospitalmanagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OfferSnapshot offerSnapshot;

    public record OfferSnapshot(Long locationId, ConsultationMode consultationMode, String clinicName,
                                String clinicAddress, BigDecimal consultationFee, UUID offerVersion) {
    }
}
