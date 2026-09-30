package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.PatientRequest;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class PatientService {

    private final PatientRepository patientRepository;
    private final UserRepository userRepository;

    public PatientService(
            PatientRepository patientRepository,
            UserRepository userRepository) {

        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
    }

    public Patient getPatientById(Long id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Patient not found"));
    }

    public Patient savePatient(Patient patient) {
        return patientRepository.save(patient);
    }

    public Patient createPatient(PatientRequest request) {

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Patient patient = new Patient();

        patient.setUser(user);
        patient.setFirstName(request.getFirstName());
        patient.setLastName(request.getLastName());
        patient.setDateOfBirth(request.getDateOfBirth());
        patient.setGender(request.getGender());
        patient.setPhone(request.getPhone());

        return patientRepository.save(patient);
    }
}