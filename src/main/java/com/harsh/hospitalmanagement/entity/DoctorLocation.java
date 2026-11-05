package com.harsh.hospitalmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@Setter
@Entity
@Table(name = "doctor_locations", uniqueConstraints =
        @UniqueConstraint(name = "uk_doctor_locations_doctor_id", columnNames = {"doctor_id", "id"}))
public class DoctorLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false, updatable = false)
    private Doctor doctor;

    @Column(name = "clinic_name", nullable = false)
    private String clinicName;

    @Column(name = "address_line_1", nullable = false)
    private String addressLine1;

    @Column(name = "address_line_2")
    private String addressLine2;

    @Column(nullable = false, length = 120)
    private String locality;

    @Column(nullable = false, length = 120)
    private String city;

    @Column(nullable = false, length = 120)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "contact_phone", nullable = false, length = 32)
    private String contactPhone;

    @Column(name = "in_clinic_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal inClinicFee;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private boolean archived;

    public String formattedAddress() {
        return Stream.of(addressLine1, addressLine2, locality, city, state, postalCode)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }
}
