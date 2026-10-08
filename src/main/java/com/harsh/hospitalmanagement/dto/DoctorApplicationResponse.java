package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class DoctorApplicationResponse {

    private Long id;
    private ApplicationStatus status;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;

    private String firstName;
    private String lastName;
    private String qualification;
    private Integer experience;

    private UserResponse user;
    private SpecializationResponse specialization;
}
