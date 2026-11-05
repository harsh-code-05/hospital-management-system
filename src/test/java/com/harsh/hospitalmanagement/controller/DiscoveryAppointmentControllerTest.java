package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.security.WithLocalUser;
import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DiscoveryAppointmentController.class)
@WithMvcSecurity
class DiscoveryAppointmentControllerTest {
    private static final String DISCOVERY = "/api/v2/discovery/doctors/1/slots";
    private static final String BOOK = "/api/v2/appointments";
    private static final LocalDate DATE = LocalDate.of(2099, 1, 1);
    private static final UUID OFFER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String BOOKING = """
            {"slotId":2,"expectedDate":"%s","offerVersion":"%s","reason":"Consultation"}
            """.formatted(DATE, OFFER);

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AppointmentSlotService slots;
    @MockitoBean
    private AppointmentService appointments;

    @Test
    void anonymousGetCanReadDiscoveryOffers() throws Exception {
        AppointmentSlot offer = new AppointmentSlot();
        offer.setId(2L);
        offer.setDate(DATE);
        offer.setStartTime(LocalTime.of(9, 0));
        offer.setEndTime(LocalTime.of(9, 30));
        offer.setConsultationMode(ConsultationMode.VIDEO);
        offer.setFee(new BigDecimal("25.00"));
        offer.setOfferVersion(OFFER);
        when(slots.getDiscoverableOffers(1L, DATE)).thenReturn(List.of(offer));

        mvc.perform(get(DISCOVERY).param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slotId").value(2))
                .andExpect(jsonPath("$[0].offerVersion").value(OFFER.toString()));
    }

    @Test
    void discoveryRuleDoesNotPermitAnonymousPost() throws Exception {
        mvc.perform(post(DISCOVERY).with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(slots, appointments);
    }

    @Test
    void anonymousBookingWithCsrfStillRequiresAuthentication() throws Exception {
        mvc.perform(post(BOOK).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BOOKING))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(appointments);
    }

    @Test
    @WithLocalUser(username = "doctor@test.invalid", roles = "DOCTOR")
    void doctorCannotBookEvenWithCsrf() throws Exception {
        mvc.perform(post(BOOK).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BOOKING))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointments);
    }

    @Test
    @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void patientBookingStillRequiresCsrf() throws Exception {
        mvc.perform(post(BOOK).contentType(MediaType.APPLICATION_JSON).content(BOOKING))
                .andExpect(status().isForbidden());
        verifyNoInteractions(appointments);
    }

    @Test
    @WithLocalUser(username = "patient@test.invalid", roles = "PATIENT")
    void patientWithCsrfReachesBookingAndReceivesCodedConflict() throws Exception {
        when(appointments.bookVersionedOffer(2L, DATE, OFFER, "Consultation", account("patient@test.invalid")))
                .thenThrow(new ConflictException("OFFER_CHANGED", "Refresh the available slots"));

        mvc.perform(post(BOOK).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BOOKING))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_CHANGED"));
    }
}
