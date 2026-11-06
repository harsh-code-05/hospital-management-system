package com.harsh.hospitalmanagement.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DoctorVerificationCredentialsRequest(
        @NotBlank @Size(max = 100) String registrationNumber,
        @NotBlank @Size(max = 255) String medicalCouncil,
        @NotNull @Min(1000) Integer registrationYear) {

    @JsonAnySetter
    void rejectUnexpectedField(String name, Object value) {
        throw new IllegalArgumentException("Only registration details may be supplied");
    }
}
