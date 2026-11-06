package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorVerificationResponse;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.Map;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DoctorVerificationService {

    private final DoctorManagementAccess access;
    private final DoctorVerificationHistoryRepository history;
    private final AppointmentSlotRepository slots;

    DoctorVerificationService(DoctorManagementAccess access, DoctorVerificationHistoryRepository history,
                              AppointmentSlotRepository slots) {
        this.access = access;
        this.history = history;
        this.slots = slots;
    }

    // Storage integrations pass server-issued immutable object keys after saving private evidence.
    public void replaceCredentials(Long doctorId, Credentials credentials, Authentication authentication) {
        replaceCredentials(requireDraft(doctorId, authentication), credentials);
    }

    public void updateRegistrationDetails(Long doctorId, String registrationNumber, String medicalCouncil,
                                          Integer registrationYear, Authentication authentication) {
        Doctor doctor = requireDraft(doctorId, authentication);
        // The HTTP client can edit registration details only for evidence already attached to this profile.
        replaceCredentials(doctor, new Credentials(registrationNumber, medicalCouncil, registrationYear,
                doctor.getIdProofDocumentPath(), doctor.getQualificationDocumentPath()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DoctorVerificationResponse getVerification(Long doctorId, Authentication authentication) {
        Doctor doctor = access.requireManagerForRead(doctorId, authentication).doctor();
        var events = history.findByDoctor_IdOrderByOccurredAtDescIdDesc(doctorId).stream()
                .map(event -> new DoctorVerificationResponse.HistoryEntry(event.getPreviousStatus(),
                        event.getNewStatus(), event.getActorRole(), event.getOccurredAt()))
                .toList();
        return new DoctorVerificationResponse(doctor.getId(), doctor.getVerificationStatus(),
                doctor.getRegistrationNumber(), doctor.getMedicalCouncil(), doctor.getRegistrationYear(), events);
    }

    private Doctor requireDraft(Long doctorId, Authentication authentication) {
        var manager = access.requireManager(doctorId, authentication);
        Doctor doctor = manager.doctor();
        if (manager.actor().getRole() != Role.DOCTOR) {
            throw new ForbiddenException("Only the doctor can provide verification evidence");
        }
        if (!isDraft(doctor.getVerificationStatus())) {
            throw new ConflictException("Evidence can only be replaced before submission or after rejection");
        }
        return doctor;
    }

    private void replaceCredentials(Doctor doctor, Credentials credentials) {
        if (credentials.registrationNumber() == null || credentials.registrationNumber().isBlank()
                || credentials.registrationNumber().strip().length() > 100
                || credentials.medicalCouncil() == null || credentials.medicalCouncil().isBlank()
                || credentials.medicalCouncil().strip().length() > 255
                || credentials.registrationYear() == null || credentials.registrationYear() < 1000
                || credentials.registrationYear() > Year.now().getValue()
                || !isOwnedKey(doctor.getId(), DocumentKind.ID_PROOF, credentials.idProofKey())
                || !isOwnedKey(doctor.getId(), DocumentKind.QUALIFICATION, credentials.qualificationKey())) {
            throw new BadRequestException("Valid registration details and doctor-owned verification evidence are required");
        }

        doctor.setRegistrationNumber(credentials.registrationNumber().strip());
        doctor.setMedicalCouncil(credentials.medicalCouncil().strip());
        doctor.setRegistrationYear(credentials.registrationYear());
        doctor.setIdProofDocumentPath(credentials.idProofKey());
        doctor.setQualificationDocumentPath(credentials.qualificationKey());
    }

    // The private storage adapter uses this reference internally. It is not part of any response DTO.
    public String authorizedDocumentKey(Long doctorId, DocumentKind kind, Authentication authentication) {
        Doctor doctor = access.requireManager(doctorId, authentication).doctor();
        String key = kind == DocumentKind.ID_PROOF
                ? doctor.getIdProofDocumentPath() : doctor.getQualificationDocumentPath();
        if (!isOwnedKey(doctorId, kind, key)) {
            throw new ResourceNotFoundException("Verification document not found");
        }
        return key;
    }

    public VerificationStatus transition(Long doctorId, VerificationStatus next, String notes,
                                         Authentication authentication) {
        var manager = access.requireManager(doctorId, authentication);
        Doctor doctor = manager.doctor();
        VerificationStatus previous = doctor.getVerificationStatus();
        boolean owner = manager.actor().getRole() == Role.DOCTOR;
        if (owner ? next != VerificationStatus.SUBMITTED : next == VerificationStatus.SUBMITTED) {
            throw new ForbiddenException("This actor cannot make that verification decision");
        }
        boolean allowed = switch (previous) {
            case UNVERIFIED, REJECTED -> next == VerificationStatus.SUBMITTED;
            case SUBMITTED, VERIFIED -> next == VerificationStatus.UNDER_REVIEW;
            case UNDER_REVIEW -> next == VerificationStatus.VERIFIED || next == VerificationStatus.REJECTED;
        };
        if (!allowed) {
            throw new ConflictException("Invalid verification status transition");
        }

        String idProof = doctor.getIdProofDocumentPath();
        String qualification = doctor.getQualificationDocumentPath();
        if (doctor.getRegistrationNumber() == null || doctor.getMedicalCouncil() == null
                || doctor.getRegistrationYear() == null || !isOwnedKey(doctorId, DocumentKind.ID_PROOF, idProof)
                || !isOwnedKey(doctorId, DocumentKind.QUALIFICATION, qualification)) {
            throw new BadRequestException("Registration details and both verification documents are required");
        }

        doctor.setVerificationStatus(next);
        history.save(new DoctorVerificationHistory(doctor, manager.actor(),
                owner ? VerificationActorRole.DOCTOR : VerificationActorRole.ADMIN, previous, next, notes,
                Map.of("idProofVersion", idProof.substring(idProof.lastIndexOf('/') + 1),
                        "qualificationVersion", qualification.substring(qualification.lastIndexOf('/') + 1))));
        if (previous == VerificationStatus.VERIFIED) {
            slots.retireVersionedOffers(doctorId);
        }
        return next;
    }

    private boolean isDraft(VerificationStatus status) {
        return status == VerificationStatus.UNVERIFIED || status == VerificationStatus.REJECTED;
    }

    private boolean isOwnedKey(Long doctorId, DocumentKind kind, String key) {
        return key != null && key.matches("verification/" + doctorId + "/" + kind.segment
                + "/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    public enum DocumentKind {
        ID_PROOF("id-proof"), QUALIFICATION("qualification");

        private final String segment;

        DocumentKind(String segment) {
            this.segment = segment;
        }

        public String pathSegment() {
            return segment;
        }
    }

    public record Credentials(String registrationNumber, String medicalCouncil, Integer registrationYear,
                              String idProofKey, String qualificationKey) {
    }
}
