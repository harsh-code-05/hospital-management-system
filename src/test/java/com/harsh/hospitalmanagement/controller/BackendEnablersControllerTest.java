package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.dto.*;
import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import com.harsh.hospitalmanagement.security.WithLocalUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        SpecializationController.class,
        DoctorController.class,
        PatientController.class,
        AppointmentSlotController.class,
        GlobalExceptionHandler.class
})
@WithMvcSecurity
class BackendEnablersControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpecializationService specializationService;

    @MockitoBean
    private DoctorService doctorService;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private AppointmentSlotService appointmentSlotService;

    @Test
    void getAllSpecializations_shouldReturn200_publiclyAccessible() throws Exception {
        Specialization s = new Specialization();
        s.setId(1L);
        s.setName("Cardiology");
        s.setDescription("Heart care");

        when(specializationService.getAllSpecializations()).thenReturn(List.of(s));

        mockMvc.perform(get("/api/specializations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Cardiology"));
    }

    @Test
    void getSpecializationById_shouldRequireAuthentication_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/specializations/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getSpecializationById_shouldReturn200_whenAuthenticated() throws Exception {
        Specialization s = new Specialization();
        s.setId(1L);
        s.setName("Cardiology");
        s.setDescription("Heart care");

        when(specializationService.getSpecializationById(1L)).thenReturn(s);

        mockMvc.perform(get("/api/specializations/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Cardiology"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getAllDoctors_shouldReturn200_whenAuthenticated() throws Exception {
        User u = new User();
        u.setId(2L);
        u.setEmail("doc@test.com");
        u.setRole(Role.DOCTOR);

        Specialization s = new Specialization();
        s.setId(1L);
        s.setName("Cardiology");

        Doctor d = new Doctor();
        d.setId(5L);
        d.setFirstName("John");
        d.setLastName("Doe");
        d.setQualification("MD");
        d.setExperience(10);
        d.setUser(u);
        d.setSpecialization(s);

        when(doctorService.getAllDoctors()).thenReturn(List.of(d));

        mockMvc.perform(get("/api/doctors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].firstName").value("John"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getMyPatientProfile_shouldReturn200_whenAuthenticated() throws Exception {
        User u = new User();
        u.setId(3L);
        u.setEmail("patient@test.com");
        u.setRole(Role.PATIENT);

        Patient p = new Patient();
        p.setId(7L);
        p.setFirstName("Jane");
        p.setLastName("Patient");
        p.setUser(u);

        when(patientService.getPatientByUserId(account("patient@test.com").id())).thenReturn(p);

        mockMvc.perform(get("/api/patients/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.firstName").value("Jane"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getAvailableSlots_shouldReturn200_whenAuthenticated() throws Exception {
        User u = new User();
        u.setId(2L);
        u.setEmail("doc@test.com");
        u.setRole(Role.DOCTOR);

        Specialization spec = new Specialization();
        spec.setId(1L);
        spec.setName("Cardiology");

        Doctor d = new Doctor();
        d.setId(5L);
        d.setUser(u);
        d.setSpecialization(spec);

        AppointmentSlot slot = new AppointmentSlot();
        slot.setId(10L);
        slot.setDoctor(d);
        slot.setDate(LocalDate.of(2026, 10, 15));
        slot.setStartTime(LocalTime.of(10, 0));
        slot.setEndTime(LocalTime.of(10, 30));
        slot.setStatus(SlotStatus.AVAILABLE);

        when(appointmentSlotService.getAvailableSlots(eq(5L), any())).thenReturn(List.of(slot));

        mockMvc.perform(get("/api/appointment-slots/available")
                        .param("doctorId", "5")
                        .param("date", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }
}
