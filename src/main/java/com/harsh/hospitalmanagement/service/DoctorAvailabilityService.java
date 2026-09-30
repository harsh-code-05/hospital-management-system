package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import org.springframework.stereotype.Service;

@Service
public class DoctorAvailabilityService {

    private final DoctorAvailabilityRepository doctorAvailabilityRepository;

    public DoctorAvailabilityService(DoctorAvailabilityRepository doctorAvailabilityRepository) {
        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
    }

    public DoctorAvailability getAvailabilityById(Long id) {
        return doctorAvailabilityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Doctor availability not found"));
    }

    public DoctorAvailability saveAvailability(DoctorAvailability availability) {
        return doctorAvailabilityRepository.save(availability);
    }
}