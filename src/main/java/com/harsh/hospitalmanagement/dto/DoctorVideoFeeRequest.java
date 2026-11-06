package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record DoctorVideoFeeRequest(
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal fee) {
}
