package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record DoctorLocationRequest(
        @NotBlank @Size(max = 255) String clinicName,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotBlank @Size(max = 120) String locality,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Size(max = 120) String state,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 32) String contactPhone,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal inClinicFee,
        @NotNull Boolean active) {
}
