package com.harsh.hospitalmanagement.dto;

import java.math.BigDecimal;

public record DoctorLocationResponse(
        Long id,
        String clinicName,
        String addressLine1,
        String addressLine2,
        String locality,
        String city,
        String state,
        String postalCode,
        String contactPhone,
        BigDecimal inClinicFee,
        boolean active) {
}
