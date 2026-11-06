package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentEvent;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface DoctorVerificationDocumentEventRepository extends Repository<DoctorVerificationDocumentEvent, Long> {

    DoctorVerificationDocumentEvent save(DoctorVerificationDocumentEvent event);

    List<DoctorVerificationDocumentEvent> findByDoctorIdOrderByOccurredAtDescIdDesc(Long doctorId);

    List<DoctorVerificationDocumentEvent> findByDocumentIdOrderByOccurredAtDescIdDesc(UUID documentId);

    List<DoctorVerificationDocumentEvent> findByCorrelationIdOrderByOccurredAtDescIdDesc(String correlationId);
}
