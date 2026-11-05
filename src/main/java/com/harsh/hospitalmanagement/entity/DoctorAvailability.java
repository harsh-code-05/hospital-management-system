package com.harsh.hospitalmanagement.entity;

import com.harsh.hospitalmanagement.enums.ConsultationMode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "doctor_availability")
public class DoctorAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;

    private LocalTime startTime;

    private LocalTime endTime;

    @Column(nullable = false)
    private long revision;

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    // DISCO-01 owns the composite FK. Hibernate writes location_id independently of doctor_id.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private DoctorLocation location;

    @Enumerated(EnumType.STRING)
    @Column(name = "consultation_mode", nullable = false, length = 16)
    private ConsultationMode consultationMode = ConsultationMode.IN_CLINIC;
}
