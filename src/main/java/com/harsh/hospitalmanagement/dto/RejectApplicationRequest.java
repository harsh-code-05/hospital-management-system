package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RejectApplicationRequest {

    @NotBlank(message = "Rejection reason is required")
    private String reason;
}
