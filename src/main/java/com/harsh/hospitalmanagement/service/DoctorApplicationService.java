package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorApplyRequest;
import com.harsh.hospitalmanagement.dto.DoctorRegisterRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorApplication;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.DoctorApplicationRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DoctorApplicationService {

    private final DoctorApplicationRepository doctorApplicationRepository;
    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final SpecializationRepository specializationRepository;
    private final PasswordEncoder passwordEncoder;

    public DoctorApplicationService(
            DoctorApplicationRepository doctorApplicationRepository,
            UserRepository userRepository,
            DoctorRepository doctorRepository,
            SpecializationRepository specializationRepository,
            PasswordEncoder passwordEncoder) {

        this.doctorApplicationRepository = doctorApplicationRepository;
        this.userRepository = userRepository;
        this.doctorRepository = doctorRepository;
        this.specializationRepository = specializationRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Public registration: creates a new User (Role.PATIENT) then submits a pending application.
     * The user does NOT receive Role.DOCTOR until an Admin approves.
     */
    @Transactional
    public DoctorApplication registerDoctor(DoctorRegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email already exists");
        }

        Specialization specialization = specializationRepository
                .findById(request.getSpecializationId())
                .orElseThrow(() -> new ResourceNotFoundException("Specialization not found"));

        // Create user with PATIENT role — never DOCTOR until approved
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.PATIENT);
        user = userRepository.save(user);

        DoctorApplication application = new DoctorApplication();
        application.setUser(user);
        application.setSpecialization(specialization);
        application.setFirstName(request.getFirstName());
        application.setLastName(request.getLastName());
        application.setQualification(request.getQualification());
        application.setExperience(request.getExperience());
        application.setStatus(ApplicationStatus.PENDING);
        application.setCreatedAt(LocalDateTime.now());

        return doctorApplicationRepository.save(application);
    }

    /**
     * Authenticated applicant flow: an existing user (e.g., a Google OAuth PATIENT)
     * submits a doctor application without re-creating their account.
     */
    @Transactional
    public DoctorApplication applyForDoctor(DoctorApplyRequest request, String authenticatedEmail) {

        User user = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getRole() == Role.DOCTOR) {
            throw new BadRequestException("User is already a doctor");
        }

        if (doctorApplicationRepository.existsByUser_IdAndStatus(user.getId(), ApplicationStatus.PENDING)) {
            throw new BadRequestException("You already have a pending doctor application");
        }

        // Check if user already has an approved application (DOCTOR profile exists)
        if (doctorRepository.existsByUser_Id(user.getId())) {
            throw new BadRequestException("A doctor profile already exists for this user");
        }

        Specialization specialization = specializationRepository
                .findById(request.getSpecializationId())
                .orElseThrow(() -> new ResourceNotFoundException("Specialization not found"));

        DoctorApplication application = new DoctorApplication();
        application.setUser(user);
        application.setSpecialization(specialization);
        application.setFirstName(request.getFirstName());
        application.setLastName(request.getLastName());
        application.setQualification(request.getQualification());
        application.setExperience(request.getExperience());
        application.setStatus(ApplicationStatus.PENDING);
        application.setCreatedAt(LocalDateTime.now());

        return doctorApplicationRepository.save(application);
    }

    /**
     * Returns all PENDING doctor applications (ADMIN only).
     */
    public List<DoctorApplication> getPendingApplications() {
        return doctorApplicationRepository.findByStatus(ApplicationStatus.PENDING);
    }

    /**
     * Returns a specific doctor application by ID (ADMIN only).
     */
    public DoctorApplication getApplicationById(Long id) {
        return doctorApplicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor application not found"));
    }

    /**
     * Approves a pending application: promotes User to DOCTOR and creates Doctor profile.
     */
    @Transactional
    public DoctorApplication approveApplication(Long id, String adminEmail) {

        DoctorApplication application = doctorApplicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor application not found"));

        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new BadRequestException("Only pending applications can be approved");
        }

        User adminUser = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));

        // Promote the applicant's role
        User applicant = application.getUser();
        applicant.setRole(Role.DOCTOR);
        userRepository.save(applicant);

        // Create the Doctor profile using data from the application
        Doctor doctor = new Doctor();
        doctor.setUser(applicant);
        doctor.setSpecialization(application.getSpecialization());
        doctor.setFirstName(application.getFirstName());
        doctor.setLastName(application.getLastName());
        doctor.setQualification(application.getQualification());
        doctor.setExperience(application.getExperience());
        doctorRepository.save(doctor);

        // Update application state
        application.setStatus(ApplicationStatus.APPROVED);
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewedBy(adminUser);

        return doctorApplicationRepository.save(application);
    }

    /**
     * Rejects a pending application. The user remains PATIENT; no Doctor profile is created.
     */
    @Transactional
    public DoctorApplication rejectApplication(Long id, String reason, String adminEmail) {

        DoctorApplication application = doctorApplicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor application not found"));

        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new BadRequestException("Only pending applications can be rejected");
        }

        User adminUser = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));

        // User role intentionally NOT changed — stays PATIENT
        application.setStatus(ApplicationStatus.REJECTED);
        application.setRejectionReason(reason);
        application.setReviewedAt(LocalDateTime.now());
        application.setReviewedBy(adminUser);

        return doctorApplicationRepository.save(application);
    }

    /**
     * Allows an applicant to check the status of their own application.
     */
    public DoctorApplication getMyApplication(String authenticatedEmail) {

        User user = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return doctorApplicationRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No doctor application found for this user"));
    }
}
