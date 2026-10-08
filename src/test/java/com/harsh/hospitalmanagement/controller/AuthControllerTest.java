package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SecurityConfig;
import com.harsh.hospitalmanagement.dto.LoginRequest;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.service.AuditLogService;
import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthController.class, GlobalExceptionHandler.class})
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private CustomOidcUserService customOidcUserService;

    @Test
    void login_shouldReturn200AndLogSuccess_whenCredentialsValid() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user@test.com",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_PATIENT"))
        );

        when(authenticationManager.authenticate(any())).thenReturn(auth);

        String json = """
                {
                    "email": "user@test.com",
                    "password": "password"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@test.com"))
                .andExpect(jsonPath("$.role").value("PATIENT"));

        verify(auditLogService).logEvent(
                eq(AuditEventType.LOGIN_SUCCESS),
                eq("user@test.com"),
                eq("ROLE_PATIENT"),
                eq("User"),
                isNull(),
                contains("User logged in successfully"),
                any()
        );
    }

    @Test
    void login_shouldReturn401AndLogFailure_whenCredentialsInvalid() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        String json = """
                {
                    "email": "user@test.com",
                    "password": "wrongpassword"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        verify(auditLogService).logEvent(
                eq(AuditEventType.LOGIN_FAILURE),
                eq("user@test.com"),
                isNull(),
                eq("User"),
                isNull(),
                contains("Failed login attempt"),
                any()
        );
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void getCurrentUser_shouldReturn200AndRole_whenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("patient@test.com"))
                .andExpect(jsonPath("$.role").value("ROLE_PATIENT"));
    }

    @Test
    void getCurrentUser_shouldReturn401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not authenticated"));
    }

    @Test
    @WithMockUser(username = "patient@test.com", roles = "PATIENT")
    void logout_shouldReturn200AndClearCookie_whenAuthenticated() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(cookie().maxAge("JSESSIONID", 0));
    }

    @Test
    void logout_shouldRequireAuthentication_whenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().is3xxRedirection());
    }
}
