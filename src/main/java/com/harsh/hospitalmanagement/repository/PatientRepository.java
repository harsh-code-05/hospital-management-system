package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    Optional<Patient> findByUser_Email(String email);
}