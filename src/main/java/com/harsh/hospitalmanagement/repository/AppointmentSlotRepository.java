package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.harsh.hospitalmanagement.enums.SlotStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;


public interface AppointmentSlotRepository
        extends JpaRepository<AppointmentSlot, Long> {

    List<AppointmentSlot> findByDoctor_IdAndStatus(Long doctorId, SlotStatus status);

    List<AppointmentSlot> findByDoctor_IdAndDateAndStatus(Long doctorId, LocalDate date, SlotStatus status);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AppointmentSlot s WHERE s.id = :id")
    Optional<AppointmentSlot> findByIdForUpdate(@Param("id") Long id);


    @Query("""
        SELECT COUNT(s) > 0
        FROM AppointmentSlot s
        WHERE s.doctor.id = :doctorId
        AND s.date = :date
        AND s.startTime = :startTime
        AND s.endTime = :endTime
        """)
    boolean existsByDoctorAndDateAndTime(
            @Param("doctorId") Long doctorId,
            @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime
    );
}