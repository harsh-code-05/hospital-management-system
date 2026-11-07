package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse;
import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse.ClinicLocationInfo;
import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse.SpecializationInfo;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import com.harsh.hospitalmanagement.service.DoctorDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiscoveryAppointmentController.class)
@WithMvcSecurity
class DiscoveryDoctorApiControllerTest {

    private static final String DISCOVERY_DOCTORS = "/api/v2/discovery/doctors";

    @Autowired private MockMvc mvc;
    @MockitoBean private AppointmentSlotService slots;
    @MockitoBean private AppointmentService appointments;
    @MockitoBean private DoctorDiscoveryService discoveryService;

    @Test
    void anonymousGetCanDiscoverDoctors() throws Exception {
        DoctorDiscoveryResponse doc = new DoctorDiscoveryResponse(
                1L, "Alice", "Smith", "Dr. Alice Smith",
                new SpecializationInfo(10L, "Cardiology"),
                12, "MBBS, MD", "Expert cardiologist", "Female", null,
                List.of(ConsultationMode.IN_CLINIC, ConsultationMode.VIDEO),
                new BigDecimal("500.00"), new BigDecimal("600.00"), "INR",
                List.of(new ClinicLocationInfo(
                        101L, "Heart Clinic", "123 Main St, Bandra, Mumbai, Maharashtra, 400050",
                        "123 Main St", null, "Bandra", "Mumbai", "Maharashtra", "400050",
                        new BigDecimal("600.00")
                ))
        );

        when(discoveryService.discoverDoctors(null, null, null)).thenReturn(List.of(doc));

        mvc.perform(get(DISCOVERY_DOCTORS).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].doctorId").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Dr. Alice Smith"))
                .andExpect(jsonPath("$[0].specialization.name").value("Cardiology"))
                .andExpect(jsonPath("$[0].experience").value(12))
                .andExpect(jsonPath("$[0].consultationModes[0]").value("IN_CLINIC"))
                .andExpect(jsonPath("$[0].consultationModes[1]").value("VIDEO"))
                .andExpect(jsonPath("$[0].videoConsultationFee").value(500.00))
                .andExpect(jsonPath("$[0].startingInClinicFee").value(600.00))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].clinics[0].clinicName").value("Heart Clinic"))
                // Verify sensitive and unnecessary fields are not present
                .andExpect(jsonPath("$[0].clinics[0].contactPhone").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].user").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void discoverDoctorsForwardsQueryParameters() throws Exception {
        when(discoveryService.discoverDoctors(10L, "Mumbai", "VIDEO")).thenReturn(List.of());

        mvc.perform(get(DISCOVERY_DOCTORS)
                        .param("specializationId", "10")
                        .param("city", "Mumbai")
                        .param("mode", "VIDEO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void invalidSpecializationIdTypeReturns400Json() throws Exception {
        mvc.perform(get(DISCOVERY_DOCTORS).param("specializationId", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'specializationId'."));
    }

    @Test
    void negativeSpecializationIdReturns400Json() throws Exception {
        when(discoveryService.discoverDoctors(-5L, null, null))
                .thenThrow(new BadRequestException("Query parameter 'specializationId' must be a positive integer."));

        mvc.perform(get(DISCOVERY_DOCTORS).param("specializationId", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Query parameter 'specializationId' must be a positive integer."));
    }

    @Test
    void invalidModeReturns400Json() throws Exception {
        when(discoveryService.discoverDoctors(null, null, "INVALID"))
                .thenThrow(new BadRequestException("Query parameter 'mode' must be one of: IN_CLINIC, VIDEO."));

        mvc.perform(get(DISCOVERY_DOCTORS).param("mode", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Query parameter 'mode' must be one of: IN_CLINIC, VIDEO."));
    }
}
