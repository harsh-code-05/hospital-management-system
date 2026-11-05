package com.harsh.hospitalmanagement.entity;

import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "appointment_slots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_doctor_slot_time",
                        columnNames = {
                                "doctor_id",
                                "date",
                                "start_time",
                                "end_time"
                        }
                )
        }
)

public class AppointmentSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private LocalDate date;

    @Column(updatable = false)
    private LocalTime startTime;

    @Column(updatable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    private SlotStatus status;

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false, updatable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "availability_id", foreignKey = @ForeignKey(name = "fk_appointment_slot_availability"))
    private DoctorAvailability availability;

    private Long availabilityRevision;

    @Column(nullable = false)
    private boolean retired = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private DoctorLocation location;

    @Enumerated(EnumType.STRING)
    @Column(name = "consultation_mode", length = 16)
    private ConsultationMode consultationMode;

    @Column(precision = 12, scale = 2)
    private BigDecimal fee;

    @Column(name = "offer_version")
    private UUID offerVersion;

}
