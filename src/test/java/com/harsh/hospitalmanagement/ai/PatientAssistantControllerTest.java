package com.harsh.hospitalmanagement.ai;

import com.harsh.hospitalmanagement.security.WithLocalUser;
import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PatientAssistantController.class)
@WithMvcSecurity
class PatientAssistantControllerTest {
    private static final String ROOT = "/api/patient/assistant/sessions";
    private static final String BODY = "{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"content\":\"Help with the portal\"}";
    @Autowired MockMvc mvc;
    @MockitoBean PatientAssistantService service;

    @Test
    void anonymousRequestsReturn401JsonWithoutRedirect() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(post(ROOT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        mvc.perform(delete(ROOT + "/1").with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private void rejectedRole() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isForbidden());
        mvc.perform(get(ROOT + "/1/messages")).andExpect(status().isForbidden());
        mvc.perform(post(ROOT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/1/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(delete(ROOT + "/1").with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test @WithLocalUser(username = "doctor@test.invalid", roles = "DOCTOR")
    void doctorCannotAccessAnyOperation() throws Exception { rejectedRole(); }

    @Test @WithLocalUser(username = "admin@test.invalid", roles = "ADMIN")
    void adminCannotAccessAnyOperation() throws Exception { rejectedRole(); }

    @Test @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void patientIdentityComesFromPrincipalAndExtraBodyIdIsIgnored() throws Exception {
        var now = LocalDateTime.now();
        when(service.create(eq(account("patient@test.invalid")), any())).thenReturn(new AssistantDtos.Session(1L, "Chat", now, now));
        mvc.perform(post(ROOT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Chat\",\"patientId\":999}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(1));
        verify(service).create(eq(account("patient@test.invalid")), eq(new AssistantDtos.CreateSession("Chat")));
    }

    @Test @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void ownedHistorySendAndDeletionUseLocalPrincipal() throws Exception {
        var who = account("patient@test.invalid");
        var message = new AssistantDtos.Message(2L, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                AiChatMessage.Role.USER, "Help with the portal", LocalDateTime.now());
        when(service.history(who, 1L, Long.MAX_VALUE)).thenReturn(List.of(message));
        when(service.send(eq(who), eq(1L), any())).thenReturn(new AssistantDtos.Turn(List.of(message)));
        mvc.perform(get(ROOT + "/1/messages")).andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("Help with the portal"));
        mvc.perform(post(ROOT + "/1/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].id").value(2));
        mvc.perform(delete(ROOT + "/1").with(csrf())).andExpect(status().isNoContent());
        verify(service).delete(who, 1L);
    }

    @Test @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void mutationsRequireValidCsrf() throws Exception {
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/1/messages").with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(delete(ROOT + "/1")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"", "   ", "oversized"})
    @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void invalidMessagesAreRejectedBeforeService(String text) throws Exception {
        String content = text.equals("oversized") ? "x".repeat(2001) : text;
        mvc.perform(post(ROOT + "/1/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void invalidPaginationIdsAndRequestIdAreRejected() throws Exception {
        mvc.perform(get(ROOT + "?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/1/messages?before=0")).andExpect(status().isBadRequest());
        mvc.perform(delete(ROOT + "/0").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/1/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Hello\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void capacityAndRateLimitErrorsHaveSafeJson() throws Exception {
        when(service.send(any(), eq(1L), any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The assistant is busy"));
        mvc.perform(post(ROOT + "/1/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("The assistant is busy"));
        when(service.send(any(), eq(1L), any())).thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait"));
        mvc.perform(post(ROOT + "/1/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
    }

    @Test @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void obsoleteSharingFlagAndForeignPatientIdCannotChangeFaqRequest() throws Exception {
        mvc.perform(post(ROOT + "/1/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"content\":\"How do I book?\",\"includeAppointments\":true,\"patientId\":999}"))
                .andExpect(status().isOk());
        verify(service).send(eq(account("patient@test.invalid")), eq(1L),
                eq(new AssistantDtos.SendMessage(UUID.fromString("00000000-0000-0000-0000-000000000001"), "How do I book?")));
    }
}
