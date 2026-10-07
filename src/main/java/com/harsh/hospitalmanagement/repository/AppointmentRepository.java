package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByPatient_User_EmailOrderByCreatedAtDesc(String email);

    List<Appointment> findByDoctor_User_EmailOrderByCreatedAtDesc(String email);

    List<Appointment> findAllByOrderByCreatedAtDesc();
}