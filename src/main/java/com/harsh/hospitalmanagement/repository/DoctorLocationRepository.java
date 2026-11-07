package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DoctorLocationRepository extends JpaRepository<DoctorLocation, Long> {

    Optional<DoctorLocation> findByIdAndDoctor_Id(Long locationId, Long doctorId);

    Optional<DoctorLocation> findByIdAndDoctor_IdAndActiveTrueAndArchivedFalse(Long locationId, Long doctorId);

    List<DoctorLocation> findAllByDoctor_IdAndActiveTrueAndArchivedFalseOrderByIdAsc(Long doctorId);

    @Query("SELECT l FROM DoctorLocation l WHERE l.doctor.id IN :doctorIds AND l.active = true AND l.archived = false ORDER BY l.doctor.id ASC, l.id ASC")
    List<DoctorLocation> findAllActiveByDoctorIds(@Param("doctorIds") Collection<Long> doctorIds);
}
