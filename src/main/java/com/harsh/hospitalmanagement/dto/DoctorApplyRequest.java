package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DoctorApplyRequest {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotNull(message = "Specialization ID is required")
    private Long specializationId;

    @NotBlank(message = "Qualification is required")
    private String qualification;

    @NotNull(message = "Experience is required")
    @PositiveOrZero(message = "Experience cannot be negative")
    private Integer experience;
}
