package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;

public interface DoctorAvailabilityRepository
        extends JpaRepository<DoctorAvailability, Long> {

    @Query("""
            SELECT COUNT(a) > 0
            FROM DoctorAvailability a
            WHERE a.doctor.id = :doctorId
            AND a.date = :date
            AND a.startTime < :endTime
            AND a.endTime > :startTime
            """)
    boolean existsOverlappingAvailability(
            @Param("doctorId") Long doctorId,
            @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime
    );
}