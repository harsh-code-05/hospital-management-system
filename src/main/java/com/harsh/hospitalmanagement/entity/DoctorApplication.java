package com.harsh.hospitalmanagement.entity;

import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "doctor_applications")
public class DoctorApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne
    @JoinColumn(name = "specialization_id", nullable = false)
    private Specialization specialization;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String qualification;

    @Column(nullable = false)
    private Integer experience;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column
    private String rejectionReason;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime reviewedAt;

    @ManyToOne
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;
}
