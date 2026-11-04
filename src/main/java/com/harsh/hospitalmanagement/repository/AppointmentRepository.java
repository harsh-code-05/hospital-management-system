package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Appointment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    @Query("SELECT a.doctor.id FROM Appointment a WHERE a.id = :id")
    Optional<Long> findDoctorIdById(@Param("id") Long id);

    @Query("""
            SELECT COUNT(a) > 0 FROM Appointment a JOIN a.slot s
            WHERE a.doctor.id = :doctorId AND s.date = :date
            AND a.status = com.harsh.hospitalmanagement.enums.AppointmentStatus.CONFIRMED
            AND s.startTime < :endTime AND s.endTime > :startTime
            """)
    boolean existsOverlappingConfirmedAppointment(
            @Param("doctorId") Long doctorId,
            @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);

    List<Appointment> findByPatient_User_IdOrderByCreatedAtDesc(Long userId);

    List<Appointment> findByDoctor_User_IdOrderByCreatedAtDesc(Long userId);

    List<Appointment> findAllByOrderByCreatedAtDesc();
}
