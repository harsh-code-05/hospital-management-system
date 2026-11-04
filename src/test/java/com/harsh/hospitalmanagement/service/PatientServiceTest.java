package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.PatientProfileRequest;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PatientService patientService;

    private User patientUser;
    private PatientProfileRequest profileRequest;

    @BeforeEach
    void setUp() {
        patientUser = new User();
        patientUser.setId(10L);
        patientUser.setEmail("patient@example.com");
        patientUser.setRole(Role.PATIENT);

        profileRequest = new PatientProfileRequest();
        profileRequest.setFirstName("Jane");
        profileRequest.setLastName("Doe");
        profileRequest.setDateOfBirth(LocalDate.of(1995, 5, 20));
        profileRequest.setGender("FEMALE");
        profileRequest.setPhone("+1234567890");
    }

    @Test
    void createMyPatientProfile_success() {
        when(userRepository.findById(account("patient@example.com").id())).thenReturn(Optional.of(patientUser));
        when(patientRepository.findByUser_Id(account("patient@example.com").id())).thenReturn(Optional.empty());
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> {
            Patient p = invocation.getArgument(0);
            p.setId(100L);
            return p;
        });

        Patient created = patientService.createMyPatientProfile(account("patient@example.com").id(), profileRequest);

        assertNotNull(created);
        assertEquals(100L, created.getId());
        assertEquals("Jane", created.getFirstName());
        assertEquals("Doe", created.getLastName());
        assertEquals(LocalDate.of(1995, 5, 20), created.getDateOfBirth());
        assertEquals("FEMALE", created.getGender());
        assertEquals("+1234567890", created.getPhone());
        assertEquals(patientUser, created.getUser());
        verify(patientRepository).save(any(Patient.class));
    }

    @Test
    void createMyPatientProfile_userNotFound_throwsResourceNotFoundException() {
        when(userRepository.findById(account("unknown@example.com").id())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                patientService.createMyPatientProfile(account("unknown@example.com").id(), profileRequest)
        );
        verify(patientRepository, never()).save(any());
    }

    @Test
    void createMyPatientProfile_wrongRole_throwsForbiddenException() {
        User doctorUser = new User();
        doctorUser.setId(20L);
        doctorUser.setEmail("doctor@example.com");
        doctorUser.setRole(Role.DOCTOR);

        when(userRepository.findById(account("doctor@example.com").id())).thenReturn(Optional.of(doctorUser));

        assertThrows(ForbiddenException.class, () ->
                patientService.createMyPatientProfile(account("doctor@example.com").id(), profileRequest)
        );
        verify(patientRepository, never()).save(any());
    }

    @Test
    void createMyPatientProfile_existingProfile_throwsConflictException() {
        Patient existing = new Patient();
        existing.setId(50L);
        existing.setUser(patientUser);

        when(userRepository.findById(account("patient@example.com").id())).thenReturn(Optional.of(patientUser));
        when(patientRepository.findByUser_Id(account("patient@example.com").id())).thenReturn(Optional.of(existing));

        assertThrows(ConflictException.class, () ->
                patientService.createMyPatientProfile(account("patient@example.com").id(), profileRequest)
        );
        verify(patientRepository, never()).save(any());
    }

    @Test
    void createMyPatientProfile_concurrentSubmission_throwsConflictExceptionOnDataIntegrityViolation() {
        when(userRepository.findById(account("patient@example.com").id())).thenReturn(Optional.of(patientUser));
        when(patientRepository.findByUser_Id(account("patient@example.com").id())).thenReturn(Optional.empty());
        when(patientRepository.save(any(Patient.class))).thenThrow(new DataIntegrityViolationException("Unique constraint"));

        assertThrows(ConflictException.class, () ->
                patientService.createMyPatientProfile(account("patient@example.com").id(), profileRequest)
        );
    }

    @Test
    void getPatientByUserId_success() {
        Patient p = new Patient();
        p.setId(1L);
        p.setUser(patientUser);

        when(patientRepository.findByUser_Id(account("patient@example.com").id())).thenReturn(Optional.of(p));

        Patient found = patientService.getPatientByUserId(account("patient@example.com").id());
        assertNotNull(found);
        assertEquals(1L, found.getId());
    }

    @Test
    void getPatientByUserId_notFound_throwsResourceNotFoundException() {
        when(patientRepository.findByUser_Id(account("patient@example.com").id())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                patientService.getPatientByUserId(account("patient@example.com").id())
        );
    }
}
