package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse;
import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse.ClinicLocationInfo;
import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse.SpecializationInfo;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.DoctorLocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DoctorDiscoveryService {

    public static final int MAX_DISCOVERY_RESULTS = 50;

    @PersistenceContext
    private EntityManager em;

    private final DoctorLocationRepository locationRepository;

    public DoctorDiscoveryService(DoctorLocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    public List<DoctorDiscoveryResponse> discoverDoctors(Long specializationId, String city, String mode) {
        if (specializationId != null && specializationId <= 0) {
            throw new BadRequestException("Query parameter 'specializationId' must be a positive integer.");
        }

        ConsultationMode consultationMode = null;
        if (mode != null && !mode.isBlank()) {
            try {
                consultationMode = ConsultationMode.valueOf(mode.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Query parameter 'mode' must be one of: IN_CLINIC, VIDEO.");
            }
        }

        String normalizedCity = (city != null && !city.isBlank()) ? city.trim().toLowerCase(Locale.ROOT) : null;

        StringBuilder jpql = new StringBuilder(
                "SELECT DISTINCT d FROM Doctor d " +
                "JOIN FETCH d.specialization s " +
                "JOIN d.user u " +
                "WHERE d.verificationStatus = :verifiedStatus " +
                "AND u.role = :doctorRole "
        );

        Map<String, Object> params = new HashMap<>();
        params.put("verifiedStatus", VerificationStatus.VERIFIED);
        params.put("doctorRole", Role.DOCTOR);

        if (specializationId != null) {
            jpql.append("AND s.id = :specializationId ");
            params.put("specializationId", specializationId);
        }

        if (normalizedCity != null) {
            jpql.append("AND EXISTS (SELECT 1 FROM DoctorLocation l WHERE l.doctor = d " +
                    "AND l.active = true AND l.archived = false AND LOWER(TRIM(l.city)) = :city) ");
            params.put("city", normalizedCity);
        }

        if (consultationMode == ConsultationMode.IN_CLINIC) {
            if (normalizedCity == null) {
                jpql.append("AND EXISTS (SELECT 1 FROM DoctorLocation l WHERE l.doctor = d " +
                        "AND l.active = true AND l.archived = false) ");
            }
        } else if (consultationMode == ConsultationMode.VIDEO) {
            jpql.append("AND d.videoConsultationFee IS NOT NULL ");
        } else {
            // mode is null or unspecified: doctor must offer at least one mode
            jpql.append("AND (d.videoConsultationFee IS NOT NULL OR EXISTS (SELECT 1 FROM DoctorLocation l WHERE l.doctor = d AND l.active = true AND l.archived = false)) ");
        }

        jpql.append("ORDER BY d.id ASC");

        TypedQuery<Doctor> query = em.createQuery(jpql.toString(), Doctor.class);
        params.forEach(query::setParameter);
        query.setMaxResults(MAX_DISCOVERY_RESULTS);
        List<Doctor> doctors = query.getResultList();

        if (doctors.isEmpty()) {
            return List.of();
        }

        List<Long> doctorIds = doctors.stream().map(Doctor::getId).toList();
        Map<Long, List<DoctorLocation>> locationsByDoctor = locationRepository.findAllActiveByDoctorIds(doctorIds).stream()
                .collect(Collectors.groupingBy(l -> l.getDoctor().getId()));

        return doctors.stream().map(doc -> {
            List<DoctorLocation> clinics = locationsByDoctor.getOrDefault(doc.getId(), List.of());

            List<ConsultationMode> modes = new ArrayList<>();
            if (!clinics.isEmpty()) {
                modes.add(ConsultationMode.IN_CLINIC);
            }
            if (doc.getVideoConsultationFee() != null) {
                modes.add(ConsultationMode.VIDEO);
            }

            BigDecimal startingInClinicFee = clinics.stream()
                    .map(DoctorLocation::getInClinicFee)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(null);

            String displayName = "Dr. " + (doc.getFirstName() != null ? doc.getFirstName() : "")
                    + " " + (doc.getLastName() != null ? doc.getLastName() : "");
            displayName = displayName.trim();

            List<ClinicLocationInfo> clinicDtos = clinics.stream().map(l -> new ClinicLocationInfo(
                    l.getId(),
                    l.getClinicName(),
                    l.formattedAddress(),
                    l.getAddressLine1(),
                    l.getAddressLine2(),
                    l.getLocality(),
                    l.getCity(),
                    l.getState(),
                    l.getPostalCode(),
                    l.getInClinicFee()
            )).toList();

            return new DoctorDiscoveryResponse(
                    doc.getId(),
                    doc.getFirstName(),
                    doc.getLastName(),
                    displayName,
                    new SpecializationInfo(doc.getSpecialization().getId(), doc.getSpecialization().getName()),
                    doc.getExperience(),
                    doc.getQualification(),
                    doc.getBio(),
                    doc.getGender(),
                    doc.getProfilePhotoUrl(),
                    modes,
                    doc.getVideoConsultationFee(),
                    startingInClinicFee,
                    "INR",
                    clinicDtos
            );
        }).toList();
    }
}
