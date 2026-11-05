package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.security.AuthenticationAudit;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.AuditLogService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.allOf;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@WithMvcSecurity
@Import(AuthenticationAudit.class)
class AuthControllerTest {
    private static final String CONTEXT_KEY = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private static final String LOGIN = """
            {"email":"user@test.invalid","password":"fixture-password"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    void passwordLoginRotatesSessionAndCsrfThenOnlyProtectedPostCanLogOut() throws Exception {
        stubValidCredentials();
        MockHttpSession session = new MockHttpSession();
        String beforeLogin = session.getId();
        String preLoginToken = csrfToken(session);

        mockMvc.perform(post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authenticationManager);

        mockMvc.perform(post("/api/auth/login").session(session)
                        .header("X-CSRF-TOKEN", preLoginToken).contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@test.invalid"))
                .andExpect(jsonPath("$.role").value("PATIENT"));

        assertThat(session.getId()).isNotEqualTo(beforeLogin);
        assertThat(session.getAttribute(CONTEXT_KEY)).isNotNull();
        verify(auditLogService).logEvent(eq(AuditEventType.LOGIN_SUCCESS), eq("user@test.invalid"),
                eq("ROLE_PATIENT"), eq("User"), eq(42L), contains("password"), any());

        mockMvc.perform(get("/logout").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", preLoginToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("user@test.invalid"))
                .andExpect(jsonPath("$.role").value("ROLE_PATIENT"));

        String postLoginToken = csrfToken(session);
        mockMvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", postLoginToken))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void successfulCredentialsWithoutAuditReturn503AndDoNotEstablishSession() throws Exception {
        stubValidCredentials();
        when(auditLogService.logEvent(eq(AuditEventType.LOGIN_SUCCESS), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("test audit outage"));
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Sign-in temporarily unavailable"));

        assertThat(session.getId()).isEqualTo(before);
        assertThat(session.getAttribute(CONTEXT_KEY)).isNull();
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void failedCredentialsAreStillRejectedWhenFailureAuditIsUnavailable() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("rejected"));
        when(auditLogService.logEvent(eq(AuditEventType.LOGIN_FAILURE), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("test audit outage"));

        mockMvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        verify(auditLogService).logEvent(eq(AuditEventType.LOGIN_FAILURE), eq("user@test.invalid"),
                isNull(), eq("User"), isNull(), eq("Failed login attempt"), any());
    }

    @Test
    void repeatedFailedAttemptsThrottleBeforeAuthenticationRegardlessOfEmailOrForwardedHeader() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("rejected"));

        for (int i = 0; i <= 10; i++) {
            var result = mockMvc.perform(post("/api/auth/login").with(csrf())
                    .with(request -> {
                        request.setRemoteAddr("198.51.100.23");
                        return request;
                    })
                    .header("X-Forwarded-For", "203.0.113." + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(LOGIN.replace("user@", "user" + i + "@")));
            if (i < 10) {
                result.andExpect(status().isUnauthorized());
            } else {
                result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
            }
        }

        verify(authenticationManager, times(10)).authenticate(any());
        verify(auditLogService, times(10)).logEvent(eq(AuditEventType.LOGIN_FAILURE),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void oldEmailBasedSessionsMustAuthenticateAgain() throws Exception {
        var legacyPrincipal = org.springframework.security.core.userdetails.User
                .withUsername("legacy@test.invalid").password("unused").roles("ADMIN").build();
        var legacyAuth = UsernamePasswordAuthenticationToken.authenticated(
                legacyPrincipal, null, legacyPrincipal.getAuthorities());
        var session = new MockHttpSession();
        session.setAttribute(CONTEXT_KEY, new SecurityContextImpl(legacyAuth));

        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void meRequiresAnAuthenticatedSession() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void browserNavigationStillRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/protected-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
    }

    @Test
    void googleAuthorizationStillRedirectsWithOAuthState() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", allOf(startsWith("https://accounts.google.com/"),
                        containsString("state="), containsString("redirect_uri="))));
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        String response = mockMvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    private void stubValidCredentials() {
        var principal = new LocalAccount.PasswordPrincipal(new LocalAccount(42L, "user@test.invalid"),
                "unused", Role.PATIENT);
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
