package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorApplication;
import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DoctorApplicationRepository extends JpaRepository<DoctorApplication, Long> {

    List<DoctorApplication> findByStatus(ApplicationStatus status);

    Optional<DoctorApplication> findByUser_Id(Long userId);

    boolean existsByUser_IdAndStatus(Long userId, ApplicationStatus status);
}
