package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SecurityConfig;
import com.harsh.hospitalmanagement.dto.*;
import com.harsh.hospitalmanagement.entity.DoctorApplication;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import com.harsh.hospitalmanagement.service.DoctorApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {DoctorApplicationController.class, GlobalExceptionHandler.class})
@Import(SecurityConfig.class)
class DoctorApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DoctorApplicationService doctorApplicationService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

    // -----------------------------------------------------------------------
    // Helper: build a minimal DoctorApplication with all non-null fields
    // -----------------------------------------------------------------------

    private DoctorApplication buildApplication() {
        User user = new User();
        user.setId(1L);
        user.setEmail("applicant@example.com");
        user.setRole(Role.PATIENT);

        Specialization spec = new Specialization();
        spec.setId(1L);
        spec.setName("Cardiology");
        spec.setDescription("Heart diseases");

        DoctorApplication app = new DoctorApplication();
        app.setId(1L);
        app.setUser(user);
        app.setSpecialization(spec);
        app.setFirstName("Alice");
        app.setLastName("Smith");
        app.setQualification("MBBS");
        app.setExperience(5);
        app.setStatus(ApplicationStatus.PENDING);
        app.setCreatedAt(LocalDateTime.now());
        return app;
    }

    // -----------------------------------------------------------------------
    // POST /api/doctor-applications/register — public endpoint
    // -----------------------------------------------------------------------

    @Test
    void registerDoctor_shouldReturn200_whenPublicAndValid() throws Exception {

        when(doctorApplicationService.registerDoctor(any(DoctorRegisterRequest.class)))
                .thenReturn(buildApplication());

        String json = """
                {
                    "email": "new@example.com",
                    "password": "password123",
                    "firstName": "Alice",
                    "lastName": "Smith",
                    "specializationId": 1,
                    "qualification": "MBBS",
                    "experience": 5
                }
                """;

        mockMvc.perform(post("/api/doctor-applications/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.firstName").value("Alice"));
    }

    @Test
    void registerDoctor_shouldReturn400_whenValidationFails() throws Exception {

        String json = """
                {
                    "email": "not-an-email",
                    "password": "short"
                }
                """;

        mockMvc.perform(post("/api/doctor-applications/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerDoctor_shouldReturn400_whenEmailAlreadyExists() throws Exception {

        when(doctorApplicationService.registerDoctor(any(DoctorRegisterRequest.class)))
                .thenThrow(new BadRequestException("Email already exists"));

        String json = """
                {
                    "email": "existing@example.com",
                    "password": "password123",
                    "firstName": "Alice",
                    "lastName": "Smith",
                    "specializationId": 1,
                    "qualification": "MBBS",
                    "experience": 5
                }
                """;

        mockMvc.perform(post("/api/doctor-applications/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    // -----------------------------------------------------------------------
    // POST /api/doctor-applications/apply — authenticated
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void applyForDoctor_shouldReturn200_whenAuthenticatedPatient() throws Exception {

        when(doctorApplicationService.applyForDoctor(any(DoctorApplyRequest.class), anyString()))
                .thenReturn(buildApplication());

        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Smith",
                    "specializationId": 1,
                    "qualification": "MBBS",
                    "experience": 5
                }
                """;

        mockMvc.perform(post("/api/doctor-applications/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void applyForDoctor_shouldReturn401_whenUnauthenticated() throws Exception {

        String json = """
                {
                    "firstName": "Alice",
                    "lastName": "Smith",
                    "specializationId": 1,
                    "qualification": "MBBS",
                    "experience": 5
                }
                """;

        mockMvc.perform(post("/api/doctor-applications/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is3xxRedirection());
    }

    // -----------------------------------------------------------------------
    // GET /api/doctor-applications/pending — ADMIN only
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void getPendingApplications_shouldReturn200_whenCallerIsAdmin() throws Exception {

        when(doctorApplicationService.getPendingApplications())
                .thenReturn(List.of(buildApplication()));

        mockMvc.perform(get("/api/doctor-applications/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void getPendingApplications_shouldReturn403_whenCallerIsPatient() throws Exception {

        mockMvc.perform(get("/api/doctor-applications/pending"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPendingApplications_shouldReturn401_whenUnauthenticated() throws Exception {

        mockMvc.perform(get("/api/doctor-applications/pending"))
                .andExpect(status().is3xxRedirection());
    }

    // -----------------------------------------------------------------------
    // GET /api/doctor-applications/{id} — ADMIN only
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void getApplicationById_shouldReturn200_whenCallerIsAdmin() throws Exception {

        when(doctorApplicationService.getApplicationById(1L))
                .thenReturn(buildApplication());

        mockMvc.perform(get("/api/doctor-applications/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void getApplicationById_shouldReturn403_whenCallerIsPatient() throws Exception {

        mockMvc.perform(get("/api/doctor-applications/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void getApplicationById_shouldReturn404_whenNotFound() throws Exception {

        when(doctorApplicationService.getApplicationById(999L))
                .thenThrow(new ResourceNotFoundException("Doctor application not found"));

        mockMvc.perform(get("/api/doctor-applications/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Doctor application not found"));
    }

    // -----------------------------------------------------------------------
    // PATCH /api/doctor-applications/{id}/approve — ADMIN only
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void approveApplication_shouldReturn200_whenCallerIsAdmin() throws Exception {

        DoctorApplication approved = buildApplication();
        approved.setStatus(ApplicationStatus.APPROVED);

        when(doctorApplicationService.approveApplication(eq(1L), anyString()))
                .thenReturn(approved);

        mockMvc.perform(patch("/api/doctor-applications/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void approveApplication_shouldReturn403_whenCallerIsPatient() throws Exception {

        mockMvc.perform(patch("/api/doctor-applications/1/approve"))
                .andExpect(status().isForbidden());
    }

    @Test
    void approveApplication_shouldReturn401_whenUnauthenticated() throws Exception {

        mockMvc.perform(patch("/api/doctor-applications/1/approve"))
                .andExpect(status().is3xxRedirection());
    }

    // -----------------------------------------------------------------------
    // PATCH /api/doctor-applications/{id}/reject — ADMIN only
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    void rejectApplication_shouldReturn200_whenCallerIsAdmin() throws Exception {

        DoctorApplication rejected = buildApplication();
        rejected.setStatus(ApplicationStatus.REJECTED);
        rejected.setRejectionReason("Insufficient credentials");

        when(doctorApplicationService.rejectApplication(eq(1L), anyString(), anyString()))
                .thenReturn(rejected);

        String json = """
                { "reason": "Insufficient credentials" }
                """;

        mockMvc.perform(patch("/api/doctor-applications/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Insufficient credentials"));
    }

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void rejectApplication_shouldReturn403_whenCallerIsPatient() throws Exception {

        String json = """
                { "reason": "some reason" }
                """;

        mockMvc.perform(patch("/api/doctor-applications/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectApplication_shouldReturn401_whenUnauthenticated() throws Exception {

        String json = """
                { "reason": "some reason" }
                """;

        mockMvc.perform(patch("/api/doctor-applications/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is3xxRedirection());
    }

    // -----------------------------------------------------------------------
    // GET /api/doctor-applications/me — authenticated
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser(username = "patient@example.com", roles = "PATIENT")
    void getMyApplication_shouldReturn200_whenAuthenticated() throws Exception {

        when(doctorApplicationService.getMyApplication("patient@example.com"))
                .thenReturn(buildApplication());

        mockMvc.perform(get("/api/doctor-applications/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getMyApplication_shouldReturn401_whenUnauthenticated() throws Exception {

        mockMvc.perform(get("/api/doctor-applications/me"))
                .andExpect(status().is3xxRedirection());
    }
}
