package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorApplication;
import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DoctorApplicationRepository extends JpaRepository<DoctorApplication, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DoctorApplication a WHERE a.id = :id")
    Optional<DoctorApplication> findByIdForUpdate(@Param("id") Long id);

    List<DoctorApplication> findByStatus(ApplicationStatus status);

    Optional<DoctorApplication> findByUser_Id(Long userId);

    boolean existsByUser_IdAndStatus(Long userId, ApplicationStatus status);
}
