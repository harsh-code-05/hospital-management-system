package com.harsh.hospitalmanagement.service;


import com.harsh.hospitalmanagement.dto.PatientProfileRequest;
import com.harsh.hospitalmanagement.dto.PatientRequest;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
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
            Long userId,
            boolean admin) {

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Patient not found"));

        if (admin) {
            return patient;
        }

        if (!userId.equals(patient.getUser().getId())) {
            throw new ForbiddenException(
                    "You are not allowed to access this patient");
        }

        return patient;
    }

    public Patient getPatientByUserId(Long userId) {
        return patientRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found for user"));
    }

    public Patient createMyPatientProfile(Long userId, PatientProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getRole() != Role.PATIENT) {
            throw new ForbiddenException("Only users with role PATIENT can create a patient profile");
        }

        if (patientRepository.findByUser_Id(userId).isPresent()) {
            throw new ConflictException("Patient profile already exists for this user");
        }

        Patient patient = new Patient();
        patient.setUser(user);
        patient.setFirstName(request.getFirstName().trim());
        patient.setLastName(request.getLastName().trim());
        patient.setDateOfBirth(request.getDateOfBirth());
        patient.setGender(request.getGender().trim());
        patient.setPhone(request.getPhone().trim());

        try {
            return patientRepository.save(patient);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Patient profile already exists for this user");
        }
    }
}