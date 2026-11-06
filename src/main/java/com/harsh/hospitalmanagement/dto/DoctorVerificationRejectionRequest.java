package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DoctorVerificationRejectionRequest(
        @NotBlank(message = "Rejection reason is required") @Size(max = 1000) String reason) {
}
