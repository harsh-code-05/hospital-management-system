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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorApplicationServiceTest {

    @Mock
    private DoctorApplicationRepository doctorApplicationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private SpecializationRepository specializationRepository;

    private PasswordEncoder passwordEncoder;
    private DoctorApplicationService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        service = new DoctorApplicationService(
                doctorApplicationRepository,
                userRepository,
                doctorRepository,
                specializationRepository,
                passwordEncoder
        );
    }

    // -----------------------------------------------------------------------
    // registerDoctor
    // -----------------------------------------------------------------------

    @Test
    void registerDoctor_shouldCreateUserWithPatientRoleAndPendingApplication() {

        DoctorRegisterRequest request = new DoctorRegisterRequest();
        request.setEmail("dr.new@example.com");
        request.setPassword("password123");
        request.setFirstName("Alice");
        request.setLastName("Smith");
        request.setSpecializationId(1L);
        request.setQualification("MBBS");
        request.setExperience(5);

        Specialization specialization = new Specialization();
        specialization.setId(1L);
        specialization.setName("Cardiology");

        when(userRepository.existsByEmail("dr.new@example.com")).thenReturn(false);
        when(specializationRepository.findById(1L)).thenReturn(Optional.of(specialization));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(10L);
            return u;
        });
        when(doctorApplicationRepository.save(any(DoctorApplication.class))).thenAnswer(inv -> {
            DoctorApplication app = inv.getArgument(0);
            app.setId(100L);
            return app;
        });

        DoctorApplication result = service.registerDoctor(request);

        // Verify user is saved with PATIENT role — never DOCTOR
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.PATIENT);

        // Verify application fields
        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(result.getFirstName()).isEqualTo("Alice");
        assertThat(result.getLastName()).isEqualTo("Smith");
        assertThat(result.getQualification()).isEqualTo("MBBS");
        assertThat(result.getExperience()).isEqualTo(5);
        assertThat(result.getSpecialization()).isEqualTo(specialization);
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    void registerDoctor_shouldThrowBadRequestException_whenEmailAlreadyExists() {

        DoctorRegisterRequest request = new DoctorRegisterRequest();
        request.setEmail("existing@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.registerDoctor(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email already exists");

        verify(userRepository, never()).save(any());
        verify(doctorApplicationRepository, never()).save(any());
    }

    @Test
    void registerDoctor_shouldThrowResourceNotFoundException_whenSpecializationNotFound() {

        DoctorRegisterRequest request = new DoctorRegisterRequest();
        request.setEmail("new@example.com");
        request.setPassword("password123");
        request.setSpecializationId(99L);

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(specializationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerDoctor(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Specialization not found");

        verify(userRepository, never()).save(any());
        verify(doctorApplicationRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // applyForDoctor
    // -----------------------------------------------------------------------

    @Test
    void applyForDoctor_shouldCreatePendingApplication_forAuthenticatedPatient() {

        User patient = new User();
        patient.setId(1L);
        patient.setEmail("patient@example.com");
        patient.setRole(Role.PATIENT);

        Specialization specialization = new Specialization();
        specialization.setId(1L);
        specialization.setName("Cardiology");

        DoctorApplyRequest request = new DoctorApplyRequest();
        request.setFirstName("Bob");
        request.setLastName("Jones");
        request.setSpecializationId(1L);
        request.setQualification("MD");
        request.setExperience(3);

        when(userRepository.findByEmail("patient@example.com")).thenReturn(Optional.of(patient));
        when(doctorApplicationRepository.existsByUser_IdAndStatus(1L, ApplicationStatus.PENDING)).thenReturn(false);
        when(doctorRepository.existsByUser_Id(1L)).thenReturn(false);
        when(specializationRepository.findById(1L)).thenReturn(Optional.of(specialization));
        when(doctorApplicationRepository.save(any(DoctorApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorApplication result = service.applyForDoctor(request, "patient@example.com");

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(result.getFirstName()).isEqualTo("Bob");
        assertThat(result.getUser()).isEqualTo(patient);
    }

    @Test
    void applyForDoctor_shouldThrowBadRequestException_whenUserIsAlreadyDoctor() {

        User doctor = new User();
        doctor.setId(2L);
        doctor.setEmail("doctor@example.com");
        doctor.setRole(Role.DOCTOR);

        when(userRepository.findByEmail("doctor@example.com")).thenReturn(Optional.of(doctor));

        DoctorApplyRequest request = new DoctorApplyRequest();

        assertThatThrownBy(() -> service.applyForDoctor(request, "doctor@example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("User is already a doctor");

        verify(doctorApplicationRepository, never()).save(any());
    }

    @Test
    void applyForDoctor_shouldThrowBadRequestException_whenApplicationAlreadyPending() {

        User patient = new User();
        patient.setId(3L);
        patient.setEmail("patient2@example.com");
        patient.setRole(Role.PATIENT);

        when(userRepository.findByEmail("patient2@example.com")).thenReturn(Optional.of(patient));
        when(doctorApplicationRepository.existsByUser_IdAndStatus(3L, ApplicationStatus.PENDING)).thenReturn(true);

        DoctorApplyRequest request = new DoctorApplyRequest();

        assertThatThrownBy(() -> service.applyForDoctor(request, "patient2@example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("You already have a pending doctor application");

        verify(doctorApplicationRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // approveApplication
    // -----------------------------------------------------------------------

    @Test
    void approveApplication_shouldPromoteUserToDoctorAndCreateDoctorProfile() {

        User applicant = new User();
        applicant.setId(5L);
        applicant.setEmail("applicant@example.com");
        applicant.setRole(Role.PATIENT);

        User admin = new User();
        admin.setId(1L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);

        Specialization specialization = new Specialization();
        specialization.setId(1L);

        DoctorApplication application = new DoctorApplication();
        application.setId(10L);
        application.setUser(applicant);
        application.setSpecialization(specialization);
        application.setFirstName("Alice");
        application.setLastName("Smith");
        application.setQualification("MBBS");
        application.setExperience(5);
        application.setStatus(ApplicationStatus.PENDING);
        application.setCreatedAt(LocalDateTime.now().minusDays(1));

        when(doctorApplicationRepository.findById(10L)).thenReturn(Optional.of(application));
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(doctorApplicationRepository.save(any(DoctorApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorApplication result = service.approveApplication(10L, "admin@example.com");

        // User must now be DOCTOR
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.DOCTOR);

        // Doctor profile must be created
        ArgumentCaptor<Doctor> doctorCaptor = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(doctorCaptor.capture());
        Doctor savedDoctor = doctorCaptor.getValue();
        assertThat(savedDoctor.getUser()).isEqualTo(applicant);
        assertThat(savedDoctor.getFirstName()).isEqualTo("Alice");
        assertThat(savedDoctor.getQualification()).isEqualTo("MBBS");

        // Application status
        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(result.getReviewedAt()).isNotNull();
        assertThat(result.getReviewedBy()).isEqualTo(admin);
    }

    @Test
    void approveApplication_shouldThrowBadRequestException_whenApplicationNotPending() {

        DoctorApplication application = new DoctorApplication();
        application.setId(11L);
        application.setStatus(ApplicationStatus.APPROVED);

        when(doctorApplicationRepository.findById(11L)).thenReturn(Optional.of(application));

        assertThatThrownBy(() -> service.approveApplication(11L, "admin@example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only pending applications can be approved");

        verify(userRepository, never()).save(any());
        verify(doctorRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // rejectApplication
    // -----------------------------------------------------------------------

    @Test
    void rejectApplication_shouldKeepUserAsPatientAndSetStatusRejected() {

        User applicant = new User();
        applicant.setId(6L);
        applicant.setEmail("applicant2@example.com");
        applicant.setRole(Role.PATIENT);

        User admin = new User();
        admin.setId(1L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);

        DoctorApplication application = new DoctorApplication();
        application.setId(20L);
        application.setUser(applicant);
        application.setStatus(ApplicationStatus.PENDING);

        when(doctorApplicationRepository.findById(20L)).thenReturn(Optional.of(application));
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(doctorApplicationRepository.save(any(DoctorApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorApplication result = service.rejectApplication(20L, "Insufficient credentials", "admin@example.com");

        // User role must NOT change
        verify(userRepository, never()).save(argThat(u -> u.getRole() == Role.DOCTOR));
        // Doctor profile must NOT be created
        verify(doctorRepository, never()).save(any());

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(result.getRejectionReason()).isEqualTo("Insufficient credentials");
        assertThat(result.getReviewedAt()).isNotNull();
        assertThat(result.getReviewedBy()).isEqualTo(admin);
        // Applicant role unchanged
        assertThat(applicant.getRole()).isEqualTo(Role.PATIENT);
    }

    @Test
    void rejectApplication_shouldThrowBadRequestException_whenApplicationNotPending() {

        DoctorApplication application = new DoctorApplication();
        application.setId(21L);
        application.setStatus(ApplicationStatus.REJECTED);

        when(doctorApplicationRepository.findById(21L)).thenReturn(Optional.of(application));

        assertThatThrownBy(() -> service.rejectApplication(21L, "reason", "admin@example.com"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only pending applications can be rejected");

        verify(doctorRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // getPendingApplications
    // -----------------------------------------------------------------------

    @Test
    void getPendingApplications_shouldReturnOnlyPendingApplications() {

        DoctorApplication pending = new DoctorApplication();
        pending.setStatus(ApplicationStatus.PENDING);

        when(doctorApplicationRepository.findByStatus(ApplicationStatus.PENDING))
                .thenReturn(List.of(pending));

        List<DoctorApplication> result = service.getPendingApplications();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    // -----------------------------------------------------------------------
    // getApplicationById
    // -----------------------------------------------------------------------

    @Test
    void getApplicationById_shouldThrowResourceNotFoundException_whenNotFound() {

        when(doctorApplicationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getApplicationById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Doctor application not found");
    }

    // -----------------------------------------------------------------------
    // getMyApplication
    // -----------------------------------------------------------------------

    @Test
    void getMyApplication_shouldThrowResourceNotFoundException_whenNoApplicationExists() {

        User user = new User();
        user.setId(7L);
        user.setEmail("noapp@example.com");

        when(userRepository.findByEmail("noapp@example.com")).thenReturn(Optional.of(user));
        when(doctorApplicationRepository.findByUser_Id(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyApplication("noapp@example.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No doctor application found for this user");
    }
}
