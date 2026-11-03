package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Appointment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);

    List<Appointment> findByPatient_User_EmailOrderByCreatedAtDesc(String email);

    List<Appointment> findByDoctor_User_EmailOrderByCreatedAtDesc(String email);

    List<Appointment> findAllByOrderByCreatedAtDesc();
}