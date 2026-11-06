package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface DoctorVerificationHistoryRepository extends Repository<DoctorVerificationHistory, Long> {

    DoctorVerificationHistory save(DoctorVerificationHistory event);

    List<DoctorVerificationHistory> findByDoctor_IdOrderByOccurredAtDescIdDesc(Long doctorId);
}
