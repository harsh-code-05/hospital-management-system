package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SecurityConfig;
import com.harsh.hospitalmanagement.dto.PatientProfileRequest;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import com.harsh.hospitalmanagement.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PatientController.class, GlobalExceptionHandler.class})
@Import(SecurityConfig.class)
class PatientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void createMyPatientProfile_shouldReturn200_whenValidRequestAndAuthenticatedPatient() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setEmail("patient@example.com");
        user.setRole(Role.PATIENT);

        Patient patient = new Patient();
        patient.setId(10L);
        patient.setFirstName("Alice");
        patient.setLastName("Brown");
        patient.setDateOfBirth(LocalDate.of(1992, 4, 15));
        patient.setGender("FEMALE");
        patient.setPhone("+1234567890");
        patient.setUser(user);

        when(patientService.createMyPatientProfile(eq("patient@example.com"), any(PatientProfileRequest.class)))
                .thenReturn(patient);

        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "1992-04-15",
                    "gender": "FEMALE",
                    "phone": "+1234567890"
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.lastName").value("Brown"))
                .andExpect(jsonPath("$.dateOfBirth").value("1992-04-15"))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.phone").value("+1234567890"))
                .andExpect(jsonPath("$.user.email").value("patient@example.com"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void createMyPatientProfile_shouldReturn400_whenMissingRequiredFields() throws Exception {
        String json = """
                {
                    "firstName": "",
                    "lastName": ""
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void createMyPatientProfile_shouldReturn400_whenFutureDateOfBirth() throws Exception {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        String json = String.format("""
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "%s",
                    "gender": "FEMALE",
                    "phone": "+1234567890"
                }
                """, tomorrow);

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.dateOfBirth").value("Date of birth must be in the past"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void createMyPatientProfile_shouldReturn400_whenInvalidPhone() throws Exception {
        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "1990-01-01",
                    "gender": "FEMALE",
                    "phone": "abc"
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.phone").value("Invalid phone number format"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void createMyPatientProfile_shouldReturn400_whenInvalidGender() throws Exception {
        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "1990-01-01",
                    "gender": "UNKNOWN_GENDER",
                    "phone": "+1234567890"
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.gender").value("Gender must be Male, Female, or Other"));
    }

    @Test
    void createMyPatientProfile_shouldRequireAuthentication_whenUnauthenticated() throws Exception {
        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "1992-04-15",
                    "gender": "FEMALE",
                    "phone": "+1234567890"
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "doctor@example.com", roles = "DOCTOR")
    void createMyPatientProfile_shouldReturn403_whenWrongRoleDoctor() throws Exception {
        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "1992-04-15",
                    "gender": "FEMALE",
                    "phone": "+1234567890"
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void createMyPatientProfile_shouldReturn403_whenWrongRoleAdmin() throws Exception {
        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "1992-04-15",
                    "gender": "FEMALE",
                    "phone": "+1234567890"
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void createMyPatientProfile_shouldReturn409_whenProfileAlreadyExists() throws Exception {
        when(patientService.createMyPatientProfile(eq("patient@example.com"), any(PatientProfileRequest.class)))
                .thenThrow(new ConflictException("Patient profile already exists for this user"));

        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Brown",
                    "dateOfBirth": "1992-04-15",
                    "gender": "FEMALE",
                    "phone": "+1234567890"
                }
                """;

        mockMvc.perform(post("/api/patients/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Patient profile already exists for this user"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void getMyPatientProfile_shouldReturn200_whenProfileExists() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setEmail("patient@example.com");
        user.setRole(Role.PATIENT);

        Patient patient = new Patient();
        patient.setId(10L);
        patient.setFirstName("Alice");
        patient.setLastName("Brown");
        patient.setDateOfBirth(LocalDate.of(1992, 4, 15));
        patient.setGender("FEMALE");
        patient.setPhone("+1234567890");
        patient.setUser(user);

        when(patientService.getPatientByEmail("patient@example.com")).thenReturn(patient);

        mockMvc.perform(get("/api/patients/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.lastName").value("Brown"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void getMyPatientProfile_shouldReturn404_whenProfileNotFound() throws Exception {
        when(patientService.getPatientByEmail("patient@example.com"))
                .thenThrow(new ResourceNotFoundException("Patient profile not found for user"));

        mockMvc.perform(get("/api/patients/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Patient profile not found for user"));
    }
}
