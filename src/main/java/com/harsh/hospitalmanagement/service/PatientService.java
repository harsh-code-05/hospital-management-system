package com.harsh.hospitalmanagement.service;


import com.harsh.hospitalmanagement.dto.PatientRequest;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.stereotype.Service;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;

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
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
    }

    public Patient savePatient(Patient patient) {
        return patientRepository.save(patient);
    }

    public Patient createPatient(PatientRequest request) {

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Patient patient = new Patient();

        patient.setUser(user);
        patient.setFirstName(request.getFirstName());
        patient.setLastName(request.getLastName());
        patient.setDateOfBirth(request.getDateOfBirth());
        patient.setGender(request.getGender());
        patient.setPhone(request.getPhone());

        return patientRepository.save(patient);
    }


    public Patient getPatientByIdForUser(
            Long patientId,
            String authenticatedEmail,
            boolean admin) {

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Patient not found"));

        if (admin) {
            return patient;
        }

        String patientEmail = patient.getUser().getEmail();

        if (!patientEmail.equals(authenticatedEmail)) {
            throw new ForbiddenException(
                    "You are not allowed to access this patient");
        }

        return patient;
    }

    public Patient getPatientByEmail(String email) {
        return patientRepository.findByUser_Email(email)
                .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found for user"));
    }
}