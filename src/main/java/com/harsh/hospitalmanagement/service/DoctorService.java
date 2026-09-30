package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final SpecializationRepository specializationRepository;

    public DoctorService(
            DoctorRepository doctorRepository,
            UserRepository userRepository,
            SpecializationRepository specializationRepository) {

        this.doctorRepository = doctorRepository;
        this.userRepository = userRepository;
        this.specializationRepository = specializationRepository;
    }

    public Doctor getDoctorById(Long id) {
        return doctorRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
    }

    public Doctor saveDoctor(Doctor doctor) {
        return doctorRepository.save(doctor);
    }

    public Doctor createDoctor(DoctorRequest request) {

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Specialization specialization =
                specializationRepository.findById(request.getSpecializationId())
                        .orElseThrow(() -> new RuntimeException("Specialization not found"));

        Doctor doctor = new Doctor();

        doctor.setUser(user);
        doctor.setSpecialization(specialization);
        doctor.setFirstName(request.getFirstName());
        doctor.setLastName(request.getLastName());
        doctor.setQualification(request.getQualification());
        doctor.setExperience(request.getExperience());

        return doctorRepository.save(doctor);
    }
}