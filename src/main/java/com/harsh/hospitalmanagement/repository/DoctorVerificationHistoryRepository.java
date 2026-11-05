package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import org.springframework.data.repository.Repository;

public interface DoctorVerificationHistoryRepository extends Repository<DoctorVerificationHistory, Long> {

    DoctorVerificationHistory save(DoctorVerificationHistory event);
}
