package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.ConsultationMode;

import java.math.BigDecimal;
import java.util.List;

public record DoctorDiscoveryResponse(
        Long doctorId,
        String firstName,
        String lastName,
        String displayName,
        SpecializationInfo specialization,
        Integer experience,
        String qualification,
        String bio,
        String gender,
        String profilePhotoUrl,
        List<ConsultationMode> consultationModes,
        BigDecimal videoConsultationFee,
        BigDecimal startingInClinicFee,
        String currency,
        List<ClinicLocationInfo> clinics
) {
    public record SpecializationInfo(Long id, String name) {}

    public record ClinicLocationInfo(
            Long id,
            String clinicName,
            String address,
            String addressLine1,
            String addressLine2,
            String locality,
            String city,
            String state,
            String postalCode,
            BigDecimal inClinicFee
    ) {}
}
