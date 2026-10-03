package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.Valid;

@Getter
@Setter
public class DoctorRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotNull(message = "Specialization ID is required")
    private Long specializationId;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Qualification is required")
    private String qualification;

    @NotNull(message = "Experience is required")
    @PositiveOrZero(message = "Experience cannot be negative")
    private Integer experience;
}