package com.harsh.hospitalmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;

    private String lastName;

    private String qualification;

    private Integer experience;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne
    @JoinColumn(name = "specialization_id", nullable = false)
    private Specialization specialization;

    @Column(name = "registration_number", length = 100)
    private String registrationNumber;

    @Column(name = "medical_council")
    private String medicalCouncil;

    @Column(name = "registration_year")
    private Integer registrationYear;

    @JsonIgnore
    @Column(name = "id_proof_document_path", columnDefinition = "text")
    private String idProofDocumentPath;

    @JsonIgnore
    @Column(name = "qualification_document_path", columnDefinition = "text")
    private String qualificationDocumentPath;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(length = 32)
    private String gender;

    @Column(name = "profile_photo_url", columnDefinition = "text")
    private String profilePhotoUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 16)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "video_consultation_fee", precision = 12, scale = 2)
    private BigDecimal videoConsultationFee;
}
