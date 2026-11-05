package com.harsh.hospitalmanagement.entity;

import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reason;

    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne
    @JoinColumn(name = "slot_id", nullable = false)
    private AppointmentSlot slot;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private DoctorLocation location;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "consultation_mode", length = 16, updatable = false)
    private ConsultationMode consultationMode;

    @Setter(AccessLevel.NONE)
    @Column(name = "clinic_name_snapshot", updatable = false)
    private String clinicNameSnapshot;

    @Setter(AccessLevel.NONE)
    @Column(name = "clinic_address_snapshot", columnDefinition = "text", updatable = false)
    private String clinicAddressSnapshot;

    @Setter(AccessLevel.NONE)
    @Column(name = "consultation_fee", precision = 12, scale = 2, updatable = false)
    private BigDecimal consultationFee;

    @Setter(AccessLevel.NONE)
    @Column(name = "offer_version", updatable = false)
    private UUID offerVersion;

    public void captureOffer(AppointmentSlot offer) {
        if (id != null || offerVersion != null || offer.getOfferVersion() == null) {
            throw new IllegalStateException("An appointment can capture a versioned offer only once, before insert");
        }
        location = offer.getLocation();
        consultationMode = offer.getConsultationMode();
        consultationFee = offer.getFee();
        offerVersion = offer.getOfferVersion();
        if (location != null) {
            clinicNameSnapshot = location.getClinicName();
            clinicAddressSnapshot = location.formattedAddress();
        }
    }
}
