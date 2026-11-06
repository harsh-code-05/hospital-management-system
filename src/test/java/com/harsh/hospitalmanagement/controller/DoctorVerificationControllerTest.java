package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.DoctorVerificationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Year;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doctor_verification_api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationControllerTest {

    private static final String VERIFICATION = "/api/doctors/{doctorId}/verification";
    private static final String CREDENTIALS = VERIFICATION + "/credentials";
    private static final String SUBMIT = VERIFICATION + "/submit";
    private static final String REVIEW = VERIFICATION + "/review";
    private static final String APPROVE = VERIFICATION + "/approve";
    private static final String REJECT = VERIFICATION + "/reject";
    private static final String REGISTRATION_DETAILS = """
            {"registrationNumber":"  REG-NEW  ","medicalCouncil":"New Council","registrationYear":2020}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationHistoryRepository history;
    @Autowired private DoctorVerificationService verificationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AppointmentSlotRepository slots;

    private Doctor owner;
    private User admin;
    private MockHttpSession ownerSession;
    private MockHttpSession adminSession;

    @BeforeEach
    void createActors() {
        owner = doctor();
        admin = user(Role.ADMIN);
        ownerSession = session(owner.getUser());
        adminSession = session(admin);
    }

    @Test
    void doctorCanReadOwnStatusWithoutEvidence() throws Exception {
        mvc.perform(get(VERIFICATION, owner.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(owner.getId().intValue()))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.history").isEmpty());
    }

    @Test
    void doctorCanSubmitRegistrationDetailsForAlreadyAttachedEvidence() throws Exception {
        var evidence = attachEvidence();

        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isNoContent());

        assertThat(storedDoctor()).satisfies(doctor -> {
            assertThat(doctor.getRegistrationNumber()).isEqualTo("REG-NEW");
            assertThat(doctor.getMedicalCouncil()).isEqualTo("New Council");
            assertThat(doctor.getRegistrationYear()).isEqualTo(2020);
            assertThat(doctor.getIdProofDocumentPath()).isEqualTo(evidence.idProofKey());
            assertThat(doctor.getQualificationDocumentPath()).isEqualTo(evidence.qualificationKey());
            assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        });
        assertThat(events()).isEmpty();
    }

    @Test
    void submissionReviewAndApprovalRecordPrincipalDerivedHistory() throws Exception {
        var evidence = attachEvidence();
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());
        mvc.perform(get(VERIFICATION, owner.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.history[0].actorRole").value("DOCTOR"));
        mvc.perform(action(REVIEW, adminSession)).andExpect(status().isNoContent());
        mvc.perform(get(VERIFICATION, owner.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        mvc.perform(action(APPROVE, adminSession)).andExpect(status().isNoContent());

        mvc.perform(get(VERIFICATION, owner.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.registrationNumber").value("REG-ORIGINAL"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].previousStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.history[0].newStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.history[0].occurredAt").exists())
                .andExpect(jsonPath("$.history[1].newStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.history[2].newStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.history[0].actorUser").doesNotExist())
                .andExpect(jsonPath("$.history[0].evidenceMetadata").doesNotExist())
                .andExpect(content().string(not(containsString(evidence.idProofKey()))))
                .andExpect(content().string(not(containsString(evidence.qualificationKey()))));

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
            var events = events();
            assertThat(events).extracting(DoctorVerificationHistory::getActorRole)
                    .containsExactly(VerificationActorRole.ADMIN, VerificationActorRole.ADMIN, VerificationActorRole.DOCTOR);
            assertThat(events).extracting(event -> event.getActorUser().getId())
                    .containsExactly(admin.getId(), admin.getId(), owner.getUser().getId());
        });
    }

    @Test
    void adminRejectsWithReasonAndDoctorCanResubmit() throws Exception {
        prepareReview();

        mvc.perform(jsonPost(REJECT, adminSession, "{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
        assertThat(events()).hasSize(2);

        mvc.perform(jsonPost(REJECT, adminSession, "{\"reason\":\"  Registration could not be confirmed  \"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(VERIFICATION, owner.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.history[0].newStatus").value("REJECTED"))
                .andExpect(jsonPath("$.history[0].notes").doesNotExist())
                .andExpect(content().string(not(containsString("Registration could not be confirmed"))));
        assertThat(events().getFirst().getNotes()).isEqualTo("Registration could not be confirmed");

        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isNoContent());
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(events().getFirst().getPreviousStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    void doctorCannotReadOrChangeAnotherDoctorsVerification() throws Exception {
        attachEvidence();
        var otherSession = session(doctor().getUser());

        mvc.perform(get(VERIFICATION, owner.getId()).session(otherSession)).andExpect(status().isForbidden());
        mvc.perform(jsonPost(CREDENTIALS, otherSession, REGISTRATION_DETAILS)).andExpect(status().isForbidden());
        mvc.perform(action(SUBMIT, otherSession)).andExpect(status().isForbidden());

        assertThat(storedDoctor().getRegistrationNumber()).isEqualTo("REG-ORIGINAL");
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(events()).isEmpty();
    }

    @Test
    void doctorCannotMakeAdminDecisionsForTheirOwnProfile() throws Exception {
        prepareReview();

        mvc.perform(action(REVIEW, ownerSession)).andExpect(status().isForbidden());
        mvc.perform(action(APPROVE, ownerSession)).andExpect(status().isForbidden());
        mvc.perform(jsonPost(REJECT, ownerSession, "{\"reason\":\"Attempted self-review\"}"))
                .andExpect(status().isForbidden());

        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
        assertThat(events()).hasSize(2);
    }

    @Test
    void adminCannotEditCredentialsOrSubmitOnBehalfOfTheDoctor() throws Exception {
        attachEvidence();
        mvc.perform(jsonPost(CREDENTIALS, adminSession, REGISTRATION_DETAILS)).andExpect(status().isForbidden());
        mvc.perform(action(SUBMIT, adminSession)).andExpect(status().isForbidden());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(events()).isEmpty();
    }

    @Test
    void patientCannotAccessAnyVerificationEndpoint() throws Exception {
        var patientSession = session(user(Role.PATIENT));
        for (var request : List.of(
                get(VERIFICATION, owner.getId()).session(patientSession),
                jsonPost(CREDENTIALS, patientSession, REGISTRATION_DETAILS),
                action(SUBMIT, patientSession), action(REVIEW, patientSession), action(APPROVE, patientSession),
                jsonPost(REJECT, patientSession, "{\"reason\":\"Attempted patient review\"}"))) {
            mvc.perform(request).andExpect(status().isForbidden());
        }
        assertThat(events()).isEmpty();
    }

    @Test
    void unauthenticatedRequestsWithValidCsrfReceiveJson401() throws Exception {
        mvc.perform(get(VERIFICATION, owner.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(post(SUBMIT, owner.getId()).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(events()).isEmpty();
    }

    @Test
    void mutationsRequireValidCsrf() throws Exception {
        attachEvidence();
        mvc.perform(post(SUBMIT, owner.getId()).session(ownerSession))
                .andExpect(status().isForbidden());
        mvc.perform(post(SUBMIT, owner.getId()).session(ownerSession).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(events()).isEmpty();
    }

    @Test
    void invalidTransitionsAndEditsDuringReviewDoNotChangeHistory() throws Exception {
        attachEvidence();
        mvc.perform(action(REVIEW, adminSession)).andExpect(status().isConflict());
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());

        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isConflict());
        mvc.perform(action(APPROVE, adminSession)).andExpect(status().isConflict());
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isConflict());

        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(events()).hasSize(1);
    }

    @Test
    void credentialEditsAndSubmissionRequireAlreadyAttachedEvidence() throws Exception {
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isBadRequest());
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isBadRequest());
        assertThat(storedDoctor().getRegistrationNumber()).isNull();
        assertThat(events()).isEmpty();
    }

    @Test
    void clientCannotSupplyEvenAWellFormedDocumentKey() throws Exception {
        var evidence = attachEvidence();
        String forgedKey = evidenceKey("id-proof");
        String request = """
                {"registrationNumber":"REG-NEW","medicalCouncil":"Council","registrationYear":2020,"idProofKey":"%s"}
                """.formatted(forgedKey);

        mvc.perform(jsonPost(CREDENTIALS, ownerSession, request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification request body"))
                .andExpect(content().string(not(containsString(forgedKey))));

        assertThat(storedDoctor().getRegistrationNumber()).isEqualTo("REG-ORIGINAL");
        assertThat(storedDoctor().getIdProofDocumentPath()).isEqualTo(evidence.idProofKey());
    }

    @Test
    void rejectsFileBodiesAndInvalidRegistrationDetails() throws Exception {
        attachEvidence();
        mvc.perform(action(CREDENTIALS, ownerSession).contentType(MediaType.APPLICATION_PDF).content("file"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, """
                {"registrationNumber":" ","medicalCouncil":"Council","registrationYear":999}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.registrationNumber").exists())
                .andExpect(jsonPath("$.errors.registrationYear").exists());
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, """
                {"registrationNumber":"REG-NEW","medicalCouncil":"Council","registrationYear":%d}
                """.formatted(Year.now().getValue() + 1))).andExpect(status().isBadRequest());
        assertThat(storedDoctor().getRegistrationNumber()).isEqualTo("REG-ORIGINAL");
    }

    @Test
    void invalidAndMissingDoctorIdsReturnClientErrors() throws Exception {
        mvc.perform(get(VERIFICATION, "invalid").session(adminSession))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get(VERIFICATION, 0).session(adminSession)).andExpect(status().isBadRequest());
        mvc.perform(get(VERIFICATION, Long.MAX_VALUE).session(adminSession)).andExpect(status().isNotFound());
    }

    @Test
    void failureAfterHistoryWriteRollsBackStatusAndHistory() throws Exception {
        prepareReview();
        verificationService.transition(owner.getId(), VerificationStatus.VERIFIED, null, authentication(admin));
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
            assertThat(events()).hasSize(4);
            assertThat(events().getFirst().getPreviousStatus()).isEqualTo(VerificationStatus.VERIFIED);
            throw new DataIntegrityViolationException("Fixture slot retirement failure");
        }).when(slots).retireVersionedOffers(owner.getId());

        mvc.perform(action(REVIEW, adminSession)).andExpect(status().isConflict());

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(events()).extracting(DoctorVerificationHistory::getNewStatus)
                    .containsExactly(VerificationStatus.VERIFIED, VerificationStatus.UNDER_REVIEW,
                            VerificationStatus.SUBMITTED);
        });
    }

    private MockHttpServletRequestBuilder action(String route, MockHttpSession session) {
        return post(route, owner.getId()).session(session).with(csrf());
    }

    private MockHttpServletRequestBuilder jsonPost(String route, MockHttpSession session, String payload) {
        return action(route, session).contentType(MediaType.APPLICATION_JSON).content(payload);
    }

    private DoctorVerificationService.Credentials attachEvidence() {
        var credentials = new DoctorVerificationService.Credentials("REG-ORIGINAL", "Council", 2019,
                evidenceKey("id-proof"), evidenceKey("qualification"));
        verificationService.replaceCredentials(owner.getId(), credentials, authentication(owner.getUser()));
        return credentials;
    }

    private String evidenceKey(String kind) {
        return "verification/" + owner.getId() + "/" + kind + "/" + UUID.randomUUID();
    }

    private void prepareReview() {
        attachEvidence();
        verificationService.transition(owner.getId(), VerificationStatus.SUBMITTED, null, authentication(owner.getUser()));
        verificationService.transition(owner.getId(), VerificationStatus.UNDER_REVIEW, null, authentication(admin));
    }

    private Doctor storedDoctor() {
        return doctors.findById(owner.getId()).orElseThrow();
    }

    private List<DoctorVerificationHistory> events() {
        return history.findByDoctor_IdOrderByOccurredAtDescIdDesc(owner.getId());
    }

    private Doctor doctor() {
        var specialization = new Specialization();
        specialization.setName("verification-test-" + UUID.randomUUID());
        var doctor = new Doctor();
        doctor.setUser(user(Role.DOCTOR));
        doctor.setSpecialization(specializations.save(specialization));
        return doctors.save(doctor);
    }

    private User user(Role role) {
        var user = new User();
        user.setEmail("verification-test-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(role);
        return users.save(user);
    }

    private static Authentication authentication(User user) {
        var principal = new LocalAccount.PasswordPrincipal(new LocalAccount(user.getId(), user.getEmail()),
                "unused", user.getRole());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static MockHttpSession session(User user) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication(user));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
