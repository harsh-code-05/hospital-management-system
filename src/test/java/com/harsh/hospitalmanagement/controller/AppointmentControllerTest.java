package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SecurityConfig;
import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AppointmentController.class, GlobalExceptionHandler.class})
@Import(SecurityConfig.class)
class AppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentService appointmentService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

    private Appointment createMockAppointment(Long id, AppointmentStatus status) {
        User patientUser = new User();
        patientUser.setId(10L);
        patientUser.setEmail("patient@test.com");
        patientUser.setRole(Role.PATIENT);

        Patient patient = new Patient();
        patient.setId(1L);
        patient.setFirstName("John");
        patient.setLastName("Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setGender("MALE");
        patient.setPhone("+1234567890");
        patient.setUser(patientUser);

        User doctorUser = new User();
        doctorUser.setId(20L);
        doctorUser.setEmail("doctor@test.com");
        doctorUser.setRole(Role.DOCTOR);

        Specialization spec = new Specialization();
        spec.setId(1L);
        spec.setName("Cardiology");
        spec.setDescription("Heart care");

        Doctor doctor = new Doctor();
        doctor.setId(2L);
        doctor.setFirstName("Sarah");
        doctor.setLastName("Doctor");
        doctor.setQualification("MD");
        doctor.setExperience(12);
        doctor.setUser(doctorUser);
        doctor.setSpecialization(spec);

        AppointmentSlot slot = new AppointmentSlot();
        slot.setId(5L);
        slot.setDate(LocalDate.of(2026, 12, 11));
        slot.setStartTime(LocalTime.of(9, 0));
        slot.setEndTime(LocalTime.of(9, 30));
        slot.setStatus(SlotStatus.BOOKED);
        slot.setDoctor(doctor);

        Appointment appointment = new Appointment();
        appointment.setId(id);
        appointment.setReason("Chest tightness");
        appointment.setCreatedAt(LocalDateTime.of(2026, 11, 1, 10, 0));
        appointment.setStatus(status);
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setSlot(slot);

        return appointment;
    }

    // -------------------------------------------------------------------------
    // POST /api/appointments (Booking)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void bookAppointment_shouldReturn200_whenValidAndAuthenticatedAsPatient() throws Exception {
        Appointment appointment = createMockAppointment(100L, AppointmentStatus.CONFIRMED);

        when(appointmentService.bookAppointment(eq(1L), eq(2L), eq(5L), eq("Chest tightness"), eq("patient@test.com")))
                .thenReturn(appointment);

        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "reason": "Chest tightness"
                }
                """;

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reason").value("Chest tightness"))
                .andExpect(jsonPath("$.patient.id").value(1L))
                .andExpect(jsonPath("$.doctor.id").value(2L))
                .andExpect(jsonPath("$.slot.id").value(5L));
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void bookAppointment_shouldReturn400_whenValidationFails() throws Exception {
        String invalidJson = """
                {
                    "patientId": null,
                    "reason": ""
                }
                """;

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.patientId").value("Patient ID is required"))
                .andExpect(jsonPath("$.errors.doctorId").value("Doctor ID is required"))
                .andExpect(jsonPath("$.errors.slotId").value("Slot ID is required"))
                .andExpect(jsonPath("$.errors.reason").value("Reason is required"));
    }

    @Test
    @WithMockUser(username = "doctor@test.com", roles = "DOCTOR")
    void bookAppointment_shouldReturn403_whenCallerIsDoctor() throws Exception {
        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "reason": "Checkup"
                }
                """;

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void bookAppointment_shouldReturn403_whenCallerIsAdmin() throws Exception {
        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "reason": "Checkup"
                }
                """;

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void bookAppointment_shouldRedirect_whenUnauthenticated() throws Exception {
        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "reason": "Checkup"
                }
                """;

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is3xxRedirection());
    }

    // -------------------------------------------------------------------------
    // GET /api/appointments
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void getAppointments_shouldReturn200_whenCallerIsPatient() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointments("patient@test.com", "ROLE_PATIENT"))
                .thenReturn(List.of(app));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100L))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].patient.user.email").value("patient@test.com"));
    }

    @Test
    @WithMockUser(username = "doctor@test.com", roles = "DOCTOR")
    void getAppointments_shouldReturn200_whenCallerIsDoctor() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointments("doctor@test.com", "ROLE_DOCTOR"))
                .thenReturn(List.of(app));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100L))
                .andExpect(jsonPath("$[0].doctor.user.email").value("doctor@test.com"));
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void getAppointments_shouldReturn200_whenCallerIsAdmin() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointments("admin@test.com", "ROLE_ADMIN"))
                .thenReturn(List.of(app));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100L));
    }

    @Test
    void getAppointments_shouldRedirect_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().is3xxRedirection());
    }

    // -------------------------------------------------------------------------
    // GET /api/appointments/{id}
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void getAppointmentById_shouldReturn200_whenAuthorized() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointmentById(100L, "patient@test.com", "ROLE_PATIENT"))
                .thenReturn(app);

        mockMvc.perform(get("/api/appointments/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void getAppointmentById_shouldReturn404_whenNotFound() throws Exception {
        when(appointmentService.getAppointmentById(999L, "patient@test.com", "ROLE_PATIENT"))
                .thenThrow(new ResourceNotFoundException("Appointment not found"));

        mockMvc.perform(get("/api/appointments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Appointment not found"));
    }

    @Test
    @WithMockUser(username = "unauthorized@test.com", roles = "PATIENT")
    void getAppointmentById_shouldReturn403_whenForbidden() throws Exception {
        when(appointmentService.getAppointmentById(100L, "unauthorized@test.com", "ROLE_PATIENT"))
                .thenThrow(new ForbiddenException("You are not allowed to access this appointment"));

        mockMvc.perform(get("/api/appointments/100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You are not allowed to access this appointment"));
    }

    @Test
    void getAppointmentById_shouldRedirect_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/appointments/100"))
                .andExpect(status().is3xxRedirection());
    }

    // -------------------------------------------------------------------------
    // PATCH /api/appointments/{id}/cancel
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void cancelAppointment_shouldReturn200_whenPatientCancels() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CANCELLED);
        app.getSlot().setStatus(SlotStatus.AVAILABLE);

        when(appointmentService.cancelAppointment(100L, "patient@test.com", "ROLE_PATIENT"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.slot.status").value("AVAILABLE"));
    }

    @Test
    @WithMockUser(username = "doctor@test.com", roles = "DOCTOR")
    void cancelAppointment_shouldReturn200_whenDoctorCancels() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CANCELLED);
        app.getSlot().setStatus(SlotStatus.AVAILABLE);

        when(appointmentService.cancelAppointment(100L, "doctor@test.com", "ROLE_DOCTOR"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void cancelAppointment_shouldReturn200_whenAdminCancels() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CANCELLED);
        app.getSlot().setStatus(SlotStatus.AVAILABLE);

        when(appointmentService.cancelAppointment(100L, "admin@test.com", "ROLE_ADMIN"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void cancelAppointment_shouldReturn400_whenAlreadyCancelled() throws Exception {
        when(appointmentService.cancelAppointment(100L, "patient@test.com", "ROLE_PATIENT"))
                .thenThrow(new BadRequestException("Only confirmed appointments can be cancelled"));

        mockMvc.perform(patch("/api/appointments/100/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Only confirmed appointments can be cancelled"));
    }

    @Test
    @WithMockUser(username = "unauthorized@test.com", roles = "PATIENT")
    void cancelAppointment_shouldReturn403_whenForbidden() throws Exception {
        when(appointmentService.cancelAppointment(100L, "unauthorized@test.com", "ROLE_PATIENT"))
                .thenThrow(new ForbiddenException("You are not allowed to cancel this appointment"));

        mockMvc.perform(patch("/api/appointments/100/cancel"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You are not allowed to cancel this appointment"));
    }

    @Test
    void cancelAppointment_shouldRedirect_whenUnauthenticated() throws Exception {
        mockMvc.perform(patch("/api/appointments/100/cancel"))
                .andExpect(status().is3xxRedirection());
    }

    // -------------------------------------------------------------------------
    // PATCH /api/appointments/{id}/complete
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(username = "doctor@test.com", roles = "DOCTOR")
    void completeAppointment_shouldReturn200_whenDoctorCompletes() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.COMPLETED);

        when(appointmentService.completeAppointment(100L, "doctor@test.com", "ROLE_DOCTOR"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void completeAppointment_shouldReturn200_whenAdminCompletes() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.COMPLETED);

        when(appointmentService.completeAppointment(100L, "admin@test.com", "ROLE_ADMIN"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void completeAppointment_shouldReturn403_whenCallerIsPatient() throws Exception {
        mockMvc.perform(patch("/api/appointments/100/complete"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "doctor@test.com", roles = "DOCTOR")
    void completeAppointment_shouldReturn400_whenAppointmentBeforeScheduledEndTime() throws Exception {
        when(appointmentService.completeAppointment(100L, "doctor@test.com", "ROLE_DOCTOR"))
                .thenThrow(new BadRequestException("Cannot complete an appointment before its scheduled end time"));

        mockMvc.perform(patch("/api/appointments/100/complete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Cannot complete an appointment before its scheduled end time"));
    }

    @Test
    @WithMockUser(username = "otherdoctor@test.com", roles = "DOCTOR")
    void completeAppointment_shouldReturn403_whenDoctorIsNotOwner() throws Exception {
        when(appointmentService.completeAppointment(100L, "otherdoctor@test.com", "ROLE_DOCTOR"))
                .thenThrow(new ForbiddenException("Only the assigned doctor or admin can complete this appointment"));

        mockMvc.perform(patch("/api/appointments/100/complete"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only the assigned doctor or admin can complete this appointment"));
    }

    @Test
    void completeAppointment_shouldRedirect_whenUnauthenticated() throws Exception {
        mockMvc.perform(patch("/api/appointments/100/complete"))
                .andExpect(status().is3xxRedirection());
    }
}
