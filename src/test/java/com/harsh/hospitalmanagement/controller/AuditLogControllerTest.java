package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SecurityConfig;
import com.harsh.hospitalmanagement.entity.AuditLog;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.service.AuditLogService;
import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuditLogController.class, GlobalExceptionHandler.class})
@Import(SecurityConfig.class)
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

    private AuditLog buildLog() {
        return new AuditLog(
                1L,
                AuditEventType.PATIENT_BOOKED_APPOINTMENT,
                "patient@test.com",
                "ROLE_PATIENT",
                "Appointment",
                10L,
                "Booked slot",
                "127.0.0.1",
                LocalDateTime.now()
        );
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void getAllAuditLogs_shouldReturn200_whenCallerIsAdmin() throws Exception {
        when(auditLogService.getAllAuditLogs(null, null)).thenReturn(List.of(buildLog()));

        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].eventType").value("PATIENT_BOOKED_APPOINTMENT"))
                .andExpect(jsonPath("$[0].performedByEmail").value("patient@test.com"));
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void getAllAuditLogs_shouldPassFilters_whenParamsProvided() throws Exception {
        when(auditLogService.getAllAuditLogs(eq(AuditEventType.LOGIN_SUCCESS), eq("admin@test.com")))
                .thenReturn(List.of(buildLog()));

        mockMvc.perform(get("/api/admin/audit-logs")
                        .param("eventType", "LOGIN_SUCCESS")
                        .param("userEmail", "admin@test.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void getAllAuditLogs_shouldReturn403_whenCallerIsPatient() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "doctor@test.com", roles = "DOCTOR")
    void getAllAuditLogs_shouldReturn403_whenCallerIsDoctor() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllAuditLogs_shouldRedirect_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void getAuditLogById_shouldReturn200_whenFound() throws Exception {
        when(auditLogService.getAuditLogById(1L)).thenReturn(buildLog());

        mockMvc.perform(get("/api/admin/audit-logs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.entityName").value("Appointment"));
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void getAuditLogById_shouldReturn404_whenNotFound() throws Exception {
        when(auditLogService.getAuditLogById(999L))
                .thenThrow(new ResourceNotFoundException("Audit log not found"));

        mockMvc.perform(get("/api/admin/audit-logs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Audit log not found"));
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void getAuditLogById_shouldReturn403_whenCallerIsPatient() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs/1"))
                .andExpect(status().isForbidden());
    }
}
