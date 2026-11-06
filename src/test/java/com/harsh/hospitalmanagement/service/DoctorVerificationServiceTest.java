package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorVerificationServiceTest {

    private static final long DOCTOR_ID = 42L;
    private static final String ID_PROOF_KEY = "verification/42/id-proof/10a497ff-716b-44bb-9a89-e9857ec03e60";
    private static final String QUALIFICATION_KEY = "verification/42/qualification/c229731e-3cc0-4eb3-89fb-b61a63e87f56";

    @Mock private DoctorManagementAccess access;
    @Mock private DoctorVerificationHistoryRepository history;
    @Mock private AppointmentSlotRepository slots;
    @Mock private Authentication authentication;

    private DoctorVerificationService service;
    private Doctor doctor;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new DoctorVerificationService(access, history, slots);
        doctor = new Doctor();
        doctor.setId(DOCTOR_ID);
        doctor.setRegistrationNumber("REG-OLD");
        doctor.setMedicalCouncil("Original Council");
        doctor.setRegistrationYear(2019);
        doctor.setIdProofDocumentPath(ID_PROOF_KEY);
        doctor.setQualificationDocumentPath(QUALIFICATION_KEY);
        actor = new User();
        actor.setRole(Role.DOCTOR);
        when(access.requireManager(DOCTOR_ID, authentication))
                .thenReturn(new DoctorManagementAccess.Access(doctor, actor));
    }

    @Test
    void registrationEditReusesExistingEvidenceAndDoesNotSubmit() {
        service.updateRegistrationDetails(DOCTOR_ID, "  REG-NEW  ", "  Council  ", 2020, authentication);

        assertThat(doctor.getRegistrationNumber()).isEqualTo("REG-NEW");
        assertThat(doctor.getMedicalCouncil()).isEqualTo("Council");
        assertThat(doctor.getRegistrationYear()).isEqualTo(2020);
        assertThat(doctor.getIdProofDocumentPath()).isEqualTo(ID_PROOF_KEY);
        assertThat(doctor.getQualificationDocumentPath()).isEqualTo(QUALIFICATION_KEY);
        assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        verifyNoInteractions(history, slots);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "verification/43/id-proof/10a497ff-716b-44bb-9a89-e9857ec03e60",
            "verification/42/qualification/10a497ff-716b-44bb-9a89-e9857ec03e60",
            "verification/42/id-proof/../10a497ff-716b-44bb-9a89-e9857ec03e60",
            "verification/42/id-proof/10a497ff-716b-44bb-9a89-e9857ec03e60.exe"
    })
    void missingForeignOrMalformedEvidenceBlocksEditingAndSubmission(String key) {
        doctor.setIdProofDocumentPath(key);

        assertThatThrownBy(() -> service.updateRegistrationDetails(
                DOCTOR_ID, "REG-NEW", "Council", 2020, authentication))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.SUBMITTED, null, authentication))
                .isInstanceOf(BadRequestException.class);

        assertThat(doctor.getRegistrationNumber()).isEqualTo("REG-OLD");
        assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        verifyNoInteractions(history, slots);
    }

    @Test
    void reopeningVerifiedDoctorRecordsAdminTransitionAndRetiresOffers() {
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);
        actor.setRole(Role.ADMIN);

        assertThat(service.transition(DOCTOR_ID, VerificationStatus.UNDER_REVIEW, null, authentication))
                .isEqualTo(VerificationStatus.UNDER_REVIEW);

        var event = ArgumentCaptor.forClass(DoctorVerificationHistory.class);
        verify(history).save(event.capture());
        assertThat(event.getValue().getActorUser()).isSameAs(actor);
        assertThat(event.getValue().getActorRole()).isEqualTo(VerificationActorRole.ADMIN);
        assertThat(event.getValue().getPreviousStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(event.getValue().getNewStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
        verify(slots).retireVersionedOffers(DOCTOR_ID);
    }
}
