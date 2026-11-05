package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import com.harsh.hospitalmanagement.security.WithLocalUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(controllers = {AppointmentController.class, GlobalExceptionHandler.class})
@WithMvcSecurity
class AppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentService appointmentService;

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
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void bookAppointment_shouldReturn200_whenValidAndAuthenticatedAsPatient() throws Exception {
        Appointment appointment = createMockAppointment(100L, AppointmentStatus.CONFIRMED);

        when(appointmentService.bookAppointment(eq(1L), eq(2L), eq(5L), eq(LocalDate.of(2026, 12, 11)),
                eq("Chest tightness"), eq(account("patient@test.com"))))
                .thenReturn(appointment);

        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "appointmentDate": "2026-12-11",
                    "reason": "Chest tightness"
                }
                """;

        mockMvc.perform(post("/api/appointments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reason").value("Chest tightness"))
                .andExpect(jsonPath("$.patient.id").value(1L))
                .andExpect(jsonPath("$.doctor.id").value(2L))
                .andExpect(jsonPath("$.slot.id").value(5L))
                .andExpect(jsonPath("$.slot.date").value("2026-12-11"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void bookAppointment_shouldReturn400_whenValidationFails() throws Exception {
        String invalidJson = """
                {
                    "patientId": null,
                    "reason": ""
                }
                """;

        mockMvc.perform(post("/api/appointments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.patientId").value("Patient ID is required"))
                .andExpect(jsonPath("$.errors.doctorId").value("Doctor ID is required"))
                .andExpect(jsonPath("$.errors.slotId").value("Slot ID is required"))
                .andExpect(jsonPath("$.errors.appointmentDate").value("Appointment date is required"))
                .andExpect(jsonPath("$.errors.reason").value("Reason is required"));
        verifyNoInteractions(appointmentService);
    }

    @Test
    @WithLocalUser(username = "doctor@test.com", roles = "DOCTOR")
    void bookAppointment_shouldReturn403_whenCallerIsDoctor() throws Exception {
        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "appointmentDate": "2026-12-11",
                    "reason": "Checkup"
                }
                """;

        mockMvc.perform(post("/api/appointments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithLocalUser(username = "admin@test.com", roles = "ADMIN")
    void bookAppointment_shouldReturn403_whenCallerIsAdmin() throws Exception {
        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "appointmentDate": "2026-12-11",
                    "reason": "Checkup"
                }
                """;

        mockMvc.perform(post("/api/appointments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void bookAppointment_shouldReturn401JsonWithoutMutation_whenSessionExpiredAndCsrfValid() throws Exception {
        String json = """
                {
                    "patientId": 1,
                    "doctorId": 2,
                    "slotId": 5,
                    "appointmentDate": "2026-12-11",
                    "reason": "Checkup"
                }
                """;

        mockMvc.perform(post("/api/appointments").with(expiredPatientSession()).with(csrf())
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Not authenticated"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(appointmentService);
    }

    @Test
    void bookAppointment_shouldReturn403WithoutMutation_whenCsrfInvalid() throws Exception {
        mockMvc.perform(post("/api/appointments").with(expiredPatientSession()).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(appointmentService);
    }

    // -------------------------------------------------------------------------
    // GET /api/appointments
    // -------------------------------------------------------------------------

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getAppointments_shouldReturn200_whenCallerIsPatient() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointments(account("patient@test.com"), "ROLE_PATIENT"))
                .thenReturn(List.of(app));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100L))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].patient.user.email").value("patient@test.com"));
    }

    @Test
    @WithLocalUser(username = "doctor@test.com", roles = "DOCTOR")
    void getAppointments_shouldReturn200_whenCallerIsDoctor() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointments(account("doctor@test.com"), "ROLE_DOCTOR"))
                .thenReturn(List.of(app));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100L))
                .andExpect(jsonPath("$[0].doctor.user.email").value("doctor@test.com"));
    }

    @Test
    @WithLocalUser(username = "admin@test.com", roles = "ADMIN")
    void getAppointments_shouldReturn200_whenCallerIsAdmin() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointments(account("admin@test.com"), "ROLE_ADMIN"))
                .thenReturn(List.of(app));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100L));
    }

    @Test
    void getAppointments_shouldReturn401Json_whenSessionExpiredEvenWithHtmlAccept() throws Exception {
        mockMvc.perform(get("/api/appointments").with(expiredPatientSession()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Not authenticated"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(appointmentService);
    }

    private RequestPostProcessor expiredPatientSession() {
        var principal = new LocalAccount.PasswordPrincipal(account("patient@test.com"), "unused", Role.PATIENT);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        String expiredId = session.getId();
        session.invalidate();
        return request -> {
            request.setRequestedSessionId(expiredId);
            request.setRequestedSessionIdValid(false);
            return request;
        };
    }

    // -------------------------------------------------------------------------
    // GET /api/appointments/{id}
    // -------------------------------------------------------------------------

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getAppointmentById_shouldReturn200_whenAuthorized() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CONFIRMED);
        when(appointmentService.getAppointmentById(100L, account("patient@test.com"), "ROLE_PATIENT"))
                .thenReturn(app);

        mockMvc.perform(get("/api/appointments/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getAppointmentById_shouldReturn404_whenNotFound() throws Exception {
        when(appointmentService.getAppointmentById(999L, account("patient@test.com"), "ROLE_PATIENT"))
                .thenThrow(new ResourceNotFoundException("Appointment not found"));

        mockMvc.perform(get("/api/appointments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Appointment not found"));
    }

    @Test
    @WithLocalUser(username = "unauthorized@test.com", roles = "PATIENT")
    void getAppointmentById_shouldReturn403_whenForbidden() throws Exception {
        when(appointmentService.getAppointmentById(100L, account("unauthorized@test.com"), "ROLE_PATIENT"))
                .thenThrow(new ForbiddenException("You are not allowed to access this appointment"));

        mockMvc.perform(get("/api/appointments/100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You are not allowed to access this appointment"));
    }

    @Test
    void getAppointmentById_shouldReturn401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/appointments/100"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PATCH /api/appointments/{id}/cancel
    // -------------------------------------------------------------------------

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void cancelAppointment_shouldReturn200_whenPatientCancels() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CANCELLED);
        app.getSlot().setStatus(SlotStatus.AVAILABLE);

        when(appointmentService.cancelAppointment(100L, account("patient@test.com"), "ROLE_PATIENT"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.slot.status").value("AVAILABLE"));
    }

    @Test
    @WithLocalUser(username = "doctor@test.com", roles = "DOCTOR")
    void cancelAppointment_shouldReturn200_whenDoctorCancels() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CANCELLED);
        app.getSlot().setStatus(SlotStatus.AVAILABLE);

        when(appointmentService.cancelAppointment(100L, account("doctor@test.com"), "ROLE_DOCTOR"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithLocalUser(username = "admin@test.com", roles = "ADMIN")
    void cancelAppointment_shouldReturn200_whenAdminCancels() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.CANCELLED);
        app.getSlot().setStatus(SlotStatus.AVAILABLE);

        when(appointmentService.cancelAppointment(100L, account("admin@test.com"), "ROLE_ADMIN"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void cancelAppointment_shouldReturn400_whenAlreadyCancelled() throws Exception {
        when(appointmentService.cancelAppointment(100L, account("patient@test.com"), "ROLE_PATIENT"))
                .thenThrow(new BadRequestException("Only confirmed appointments can be cancelled"));

        mockMvc.perform(patch("/api/appointments/100/cancel").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Only confirmed appointments can be cancelled"));
    }

    @Test
    @WithLocalUser(username = "unauthorized@test.com", roles = "PATIENT")
    void cancelAppointment_shouldReturn403_whenForbidden() throws Exception {
        when(appointmentService.cancelAppointment(100L, account("unauthorized@test.com"), "ROLE_PATIENT"))
                .thenThrow(new ForbiddenException("You are not allowed to cancel this appointment"));

        mockMvc.perform(patch("/api/appointments/100/cancel").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You are not allowed to cancel this appointment"));
    }

    @Test
    void cancelAppointment_shouldReturn401_whenUnauthenticated() throws Exception {
        mockMvc.perform(patch("/api/appointments/100/cancel").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PATCH /api/appointments/{id}/complete
    // -------------------------------------------------------------------------

    @Test
    @WithLocalUser(username = "doctor@test.com", roles = "DOCTOR")
    void completeAppointment_shouldReturn200_whenDoctorCompletes() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.COMPLETED);

        when(appointmentService.completeAppointment(100L, account("doctor@test.com"), "ROLE_DOCTOR"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/complete").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @WithLocalUser(username = "admin@test.com", roles = "ADMIN")
    void completeAppointment_shouldReturn200_whenAdminCompletes() throws Exception {
        Appointment app = createMockAppointment(100L, AppointmentStatus.COMPLETED);

        when(appointmentService.completeAppointment(100L, account("admin@test.com"), "ROLE_ADMIN"))
                .thenReturn(app);

        mockMvc.perform(patch("/api/appointments/100/complete").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void completeAppointment_shouldReturn403_whenCallerIsPatient() throws Exception {
        mockMvc.perform(patch("/api/appointments/100/complete").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithLocalUser(username = "doctor@test.com", roles = "DOCTOR")
    void completeAppointment_shouldReturn400_whenAppointmentBeforeScheduledEndTime() throws Exception {
        when(appointmentService.completeAppointment(100L, account("doctor@test.com"), "ROLE_DOCTOR"))
                .thenThrow(new BadRequestException("Cannot complete an appointment before its scheduled end time"));

        mockMvc.perform(patch("/api/appointments/100/complete").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Cannot complete an appointment before its scheduled end time"));
    }

    @Test
    @WithLocalUser(username = "otherdoctor@test.com", roles = "DOCTOR")
    void completeAppointment_shouldReturn403_whenDoctorIsNotOwner() throws Exception {
        when(appointmentService.completeAppointment(100L, account("otherdoctor@test.com"), "ROLE_DOCTOR"))
                .thenThrow(new ForbiddenException("Only the assigned doctor or admin can complete this appointment"));

        mockMvc.perform(patch("/api/appointments/100/complete").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only the assigned doctor or admin can complete this appointment"));
    }

    @Test
    void completeAppointment_shouldReturn401_whenUnauthenticated() throws Exception {
        mockMvc.perform(patch("/api/appointments/100/complete").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
