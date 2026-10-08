package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SpecializationService {

    private final SpecializationRepository specializationRepository;

    public SpecializationService(SpecializationRepository specializationRepository) {
        this.specializationRepository = specializationRepository;
    }

    public Specialization getSpecializationById(Long id) {
        return specializationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Specialization not found"));
    }

    public List<Specialization> getAllSpecializations() {
        return specializationRepository.findAll();
    }

    public Specialization saveSpecialization(Specialization specialization) {
        return specializationRepository.save(specialization);
    }
}