package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorLocationRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DoctorLocationService {

    private final DoctorManagementAccess access;
    private final DoctorLocationRepository locations;
    private final AppointmentSlotRepository slots;

    DoctorLocationService(DoctorManagementAccess access, DoctorLocationRepository locations,
                          AppointmentSlotRepository slots) {
        this.access = access;
        this.locations = locations;
        this.slots = slots;
    }

    public DoctorLocation create(Long doctorId, ClinicDetails details, Authentication authentication) {
        DoctorLocation clinic = new DoctorLocation();
        clinic.setDoctor(access.requireManager(doctorId, authentication).doctor());
        applyDetails(clinic, details);
        return locations.save(clinic);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<DoctorLocation> listActive(Long doctorId, Authentication authentication) {
        access.requireManagerForRead(doctorId, authentication);
        return locations.findAllByDoctor_IdAndActiveTrueAndArchivedFalseOrderByIdAsc(doctorId);
    }

    public DoctorLocation update(Long doctorId, Long locationId, ClinicDetails details, Authentication authentication) {
        access.requireManager(doctorId, authentication);
        DoctorLocation clinic = requireClinic(doctorId, locationId);
        if (clinic.isArchived()) {
            throw new ConflictException("An archived clinic cannot be updated");
        }
        applyDetails(clinic, details);
        slots.retireForLocation(doctorId, locationId);
        return clinic;
    }

    public void archive(Long doctorId, Long locationId, Authentication authentication) {
        access.requireManager(doctorId, authentication);
        DoctorLocation clinic = requireClinic(doctorId, locationId);
        clinic.setActive(false);
        clinic.setArchived(true);
        slots.retireForLocation(doctorId, locationId);
    }

    public void setVideoFee(Long doctorId, BigDecimal fee, Authentication authentication) {
        var doctor = access.requireManager(doctorId, authentication).doctor();
        BigDecimal validated = ConsultationTerms.requireFee(fee);
        if (doctor.getVideoConsultationFee() == null || doctor.getVideoConsultationFee().compareTo(validated) != 0) {
            doctor.setVideoConsultationFee(validated);
            slots.retireVideoOffers(doctorId);
        }
    }

    private DoctorLocation requireClinic(Long doctorId, Long locationId) {
        return locations.findByIdAndDoctor_Id(locationId, doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic not found for this doctor"));
    }

    private void applyDetails(DoctorLocation clinic, ClinicDetails details) {
        clinic.setClinicName(required(details.clinicName(), 255, "Clinic name"));
        clinic.setAddressLine1(required(details.addressLine1(), 255, "Address line 1"));
        clinic.setAddressLine2(details.addressLine2() == null || details.addressLine2().isBlank() ? null
                : required(details.addressLine2(), 255, "Address line 2"));
        clinic.setLocality(required(details.locality(), 120, "Locality"));
        clinic.setCity(required(details.city(), 120, "City"));
        clinic.setState(required(details.state(), 120, "State"));
        clinic.setPostalCode(required(details.postalCode(), 20, "Postal code"));
        clinic.setContactPhone(required(details.contactPhone(), 32, "Contact phone"));
        clinic.setInClinicFee(ConsultationTerms.requireFee(details.inClinicFee()));
        clinic.setActive(details.active());
    }

    private String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new BadRequestException(label + " is required and must be at most " + maxLength + " characters");
        }
        return value.strip();
    }

    public record ClinicDetails(String clinicName, String addressLine1, String addressLine2, String locality,
                                String city, String state, String postalCode, String contactPhone,
                                BigDecimal inClinicFee, boolean active) {
    }
}
