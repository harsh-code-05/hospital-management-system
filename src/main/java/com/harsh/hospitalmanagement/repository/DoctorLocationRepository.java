package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DoctorLocationRepository extends JpaRepository<DoctorLocation, Long> {

    Optional<DoctorLocation> findByIdAndDoctor_Id(Long locationId, Long doctorId);

    Optional<DoctorLocation> findByIdAndDoctor_IdAndActiveTrueAndArchivedFalse(Long locationId, Long doctorId);
}
