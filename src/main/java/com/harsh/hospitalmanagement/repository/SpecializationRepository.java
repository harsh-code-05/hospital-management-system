package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpecializationRepository extends JpaRepository<Specialization, Long> {
}