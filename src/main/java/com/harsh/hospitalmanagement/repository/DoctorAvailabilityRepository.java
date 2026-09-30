package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DoctorAvailabilityRepository
        extends JpaRepository<DoctorAvailability, Long> {
}