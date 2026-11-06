package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.AuditLogRepository;
import com.harsh.hospitalmanagement.repository.DoctorLocationRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import com.harsh.hospitalmanagement.service.DoctorAvailabilityService;
import com.harsh.hospitalmanagement.service.DoctorLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doctor_location_api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorLocationControllerTest {

    private static final String LOCATIONS = "/api/doctors/{doctorId}/locations";
    private static final String CLINIC = LOCATIONS + "/{locationId}";
    private static final String VIDEO_FEE = "/api/doctors/{doctorId}/video-fee";
    private static final BigDecimal INITIAL_VIDEO_FEE = new BigDecimal("45.00");

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private DoctorRepository doctors;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorLocationRepository locations;
    @Autowired private PatientRepository patients;
    @Autowired private AppointmentRepository appointments;
    @Autowired private AppointmentSlotRepository slots;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private DoctorLocationService locationService;
    @Autowired private DoctorAvailabilityService availabilityService;
    @Autowired private AppointmentSlotService slotService;
    @Autowired private AppointmentService appointmentService;
    @Autowired private PlatformTransactionManager transactionManager;

    private Doctor owner;
    private MockHttpSession ownerSession;

    @BeforeEach
    void createOwner() {
        owner = doctor();
        ownerSession = session(owner.getUser());
    }

    @Test
    void doctorCreatesClinicWithValidatedDtoAndLocationHeader() throws Exception {
        mvc.perform(post(LOCATIONS, owner.getId()).session(ownerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clinicJson("  Central Clinic  ", "25.50")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/doctors/" + owner.getId() + "/locations/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.clinicName").value("Central Clinic"))
                .andExpect(jsonPath("$.inClinicFee").value(25.50))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.doctor").doesNotExist())
                .andExpect(jsonPath("$.archived").doesNotExist());

        assertThat(locations.findAllByDoctor_IdAndActiveTrueAndArchivedFalseOrderByIdAsc(owner.getId()))
                .singleElement().satisfies(location -> assertThat(location.getClinicName()).isEqualTo("Central Clinic"));
    }

    @Test
    void clinicValidationReturnsFieldErrorsWithoutCreatingLocation() throws Exception {
        mvc.perform(post(LOCATIONS, owner.getId()).session(ownerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clinicJson(" ", "-1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.clinicName").exists())
                .andExpect(jsonPath("$.errors.inClinicFee").exists());

        assertThat(locations.findAllByDoctor_IdAndActiveTrueAndArchivedFalseOrderByIdAsc(owner.getId())).isEmpty();
    }

    @Test
    void listsOnlyActiveUnarchivedClinicsBelongingToTheDoctor() throws Exception {
        var first = clinic(owner, "First clinic", true);
        clinic(owner, "Inactive clinic", false);
        var archived = clinic(owner, "Archived clinic", true);
        locationService.archive(owner.getId(), archived.getId(), authentication(owner.getUser()));
        clinic(doctor(), "Another doctor's clinic", true);
        var last = clinic(owner, "Second clinic", true);

        mvc.perform(get(LOCATIONS, owner.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.getId().intValue()))
                .andExpect(jsonPath("$[1].id").value(last.getId().intValue()));
    }

    @Test
    void updatingOwnClinicCommitsDetailsAndRetiresItsOffers() throws Exception {
        var original = clinic(owner, "Original Clinic", true);
        var offer = offers(original, ConsultationMode.IN_CLINIC).getFirst();

        mvc.perform(put(CLINIC, owner.getId(), original.getId()).session(ownerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clinicJson("Updated Clinic", "50.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinicName").value("Updated Clinic"))
                .andExpect(jsonPath("$.inClinicFee").value(50.00));

        assertThat(locations.findById(original.getId()).orElseThrow().getClinicName()).isEqualTo("Updated Clinic");
        assertThat(slots.findById(offer.getId()).orElseThrow().isRetired()).isTrue();
    }

    @Test
    void archivingClinicRetiresOffersAndPreservesBookedAppointmentAndAudit() throws Exception {
        var clinic = clinic(owner, "Booked Clinic", true);
        var offers = offers(clinic, ConsultationMode.IN_CLINIC);
        var bookedOffer = offers.getFirst();
        var availableOffer = offers.getLast();
        var patient = new Patient();
        patient.setUser(user(Role.PATIENT));
        patients.save(patient);
        var actor = account(patient.getUser());
        var booking = appointmentService.bookVersionedOffer(bookedOffer.getId(), bookedOffer.getDate(),
                bookedOffer.getOfferVersion(), "Fixture booking", actor);

        mvc.perform(delete(CLINIC, owner.getId(), clinic.getId()).session(ownerSession).with(csrf()))
                .andExpect(status().isNoContent());

        var archived = locations.findById(clinic.getId()).orElseThrow();
        assertThat(archived.isArchived()).isTrue();
        assertThat(archived.isActive()).isFalse();
        assertThat(slots.findById(availableOffer.getId()).orElseThrow().isRetired()).isTrue();
        assertThat(slots.findById(bookedOffer.getId()).orElseThrow()).satisfies(slot -> {
            assertThat(slot.isRetired()).isTrue();
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        });
        assertThat(appointments.findById(booking.getId()).orElseThrow()).satisfies(stored -> {
            assertThat(stored.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
            assertThat(stored.getSlot().getId()).isEqualTo(bookedOffer.getId());
            assertThat(stored.getClinicNameSnapshot()).isEqualTo("Booked Clinic");
        });
        assertThat(auditLogs.findByEntityNameAndEntityIdOrderByTimestampDesc("Appointment", booking.getId()))
                .singleElement().satisfies(log -> assertThat(log.getEventType())
                        .isEqualTo(AuditEventType.PATIENT_BOOKED_APPOINTMENT));
        assertThatThrownBy(() -> appointmentService.bookVersionedOffer(availableOffer.getId(), availableOffer.getDate(),
                availableOffer.getOfferVersion(), "Retired offer", actor))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("OFFER_CHANGED"));
    }

    @Test
    void clinicArchiveAndOfferRetirementRollBackTogether() {
        var clinic = clinic(owner, "Clinic in transaction", true);
        var offer = offers(clinic, ConsultationMode.IN_CLINIC).getFirst();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            locationService.archive(owner.getId(), clinic.getId(), authentication(owner.getUser()));
            assertThat(locations.findById(clinic.getId()).orElseThrow().isArchived()).isTrue();
            assertThat(slots.findById(offer.getId()).orElseThrow().isRetired()).isTrue();
            transaction.setRollbackOnly();
        });

        var stored = locations.findById(clinic.getId()).orElseThrow();
        assertThat(stored.isArchived()).isFalse();
        assertThat(stored.isActive()).isTrue();
        assertThat(slots.findById(offer.getId()).orElseThrow().isRetired()).isFalse();
    }

    @Test
    void archivedClinicCannotBeUpdated() throws Exception {
        var clinic = clinic(owner, "Archived Clinic", true);
        locationService.archive(owner.getId(), clinic.getId(), authentication(owner.getUser()));

        mvc.perform(put(CLINIC, owner.getId(), clinic.getId()).session(ownerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clinicJson("Attempted edit", "5.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void doctorCanSetVideoFeeToZeroAndOnlyVideoOffersRetire() throws Exception {
        var clinic = clinic(owner, "In-person Clinic", true);
        var clinicOffer = offers(clinic, ConsultationMode.IN_CLINIC).getFirst();
        var videoOffer = offers(null, ConsultationMode.VIDEO).getFirst();

        mvc.perform(put(VIDEO_FEE, owner.getId()).session(ownerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fee\":0}"))
                .andExpect(status().isNoContent());

        assertThat(doctors.findById(owner.getId()).orElseThrow().getVideoConsultationFee()).isEqualByComparingTo("0.00");
        assertThat(slots.findById(videoOffer.getId()).orElseThrow().isRetired()).isTrue();
        assertThat(slots.findById(clinicOffer.getId()).orElseThrow().isRetired()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"fee\":-0.01}", "{\"fee\":12.345}", "{\"fee\":10000000000.00}", "{}"})
    void rejectsInvalidVideoFeesWithoutChangingTheStoredFee(String payload) throws Exception {
        mvc.perform(put(VIDEO_FEE, owner.getId()).session(ownerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fee").exists());

        assertThat(doctors.findById(owner.getId()).orElseThrow().getVideoConsultationFee())
                .isEqualByComparingTo(INITIAL_VIDEO_FEE);
    }

    @Test
    void rejectsNonNumericVideoFee() throws Exception {
        mvc.perform(put(VIDEO_FEE, owner.getId()).session(ownerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fee\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doctorCannotReadOrModifyAnotherDoctorsManagementResources() throws Exception {
        var clinic = clinic(owner, "Private management resource", true);
        var otherSession = session(doctor().getUser());

        mvc.perform(get(LOCATIONS, owner.getId()).session(otherSession)).andExpect(status().isForbidden());
        mvc.perform(put(CLINIC, owner.getId(), clinic.getId()).session(otherSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clinicJson("Unauthorized edit", "5.00")))
                .andExpect(status().isForbidden());
        mvc.perform(put(VIDEO_FEE, owner.getId()).session(otherSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fee\":0}"))
                .andExpect(status().isForbidden());

        assertThat(locations.findById(clinic.getId()).orElseThrow().getClinicName())
                .isEqualTo("Private management resource");
    }

    @Test
    void adminCanListAndUpdateAnotherDoctorsClinic() throws Exception {
        var clinic = clinic(owner, "Clinic managed by admin", true);
        var adminSession = session(user(Role.ADMIN));

        mvc.perform(get(LOCATIONS, owner.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(clinic.getId().intValue()));
        mvc.perform(put(CLINIC, owner.getId(), clinic.getId()).session(adminSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clinicJson("Admin edit", "30.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinicName").value("Admin edit"));
    }

    @Test
    void patientCannotAccessAnyLocationOrFeeManagementRoute() throws Exception {
        var clinic = clinic(owner, "Existing Clinic", true);
        var patientSession = session(user(Role.PATIENT));
        var payload = clinicJson("Forbidden Clinic", "10.00");
        for (var request : List.of(
                get(LOCATIONS, owner.getId()),
                post(LOCATIONS, owner.getId()).contentType(MediaType.APPLICATION_JSON).content(payload),
                put(CLINIC, owner.getId(), clinic.getId()).contentType(MediaType.APPLICATION_JSON).content(payload),
                delete(CLINIC, owner.getId(), clinic.getId()),
                put(VIDEO_FEE, owner.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"fee\":0}"))) {
            mvc.perform(request.session(patientSession).with(csrf())).andExpect(status().isForbidden());
        }
    }

    @Test
    void authenticationAndCsrfAreRequired() throws Exception {
        mvc.perform(get(LOCATIONS, owner.getId())).andExpect(status().isUnauthorized());
        mvc.perform(post(LOCATIONS, owner.getId()).session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON).content(clinicJson("No CSRF", "10.00")))
                .andExpect(status().isForbidden());
        mvc.perform(put(VIDEO_FEE, owner.getId()).session(ownerSession).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fee\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingClinicAndClinicFromAnotherDoctorReturnNotFound() throws Exception {
        var otherClinic = clinic(doctor(), "Other Clinic", true);

        mvc.perform(delete(CLINIC, owner.getId(), Long.MAX_VALUE).session(ownerSession).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Clinic not found for this doctor"));
        mvc.perform(delete(CLINIC, owner.getId(), otherClinic.getId()).session(ownerSession).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(locations.findById(otherClinic.getId()).orElseThrow().isArchived()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number"})
    void rejectsInvalidClinicIds(String clinicId) throws Exception {
        mvc.perform(delete(CLINIC, owner.getId(), clinicId).session(ownerSession).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archiveRequiresClinicIdInPath() throws Exception {
        mvc.perform(delete(LOCATIONS, owner.getId()).session(ownerSession).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    private Doctor doctor() {
        var specialization = new Specialization();
        specialization.setName("location-test-" + UUID.randomUUID());
        var doctor = new Doctor();
        doctor.setUser(user(Role.DOCTOR));
        doctor.setSpecialization(specializations.save(specialization));
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);
        doctor.setVideoConsultationFee(INITIAL_VIDEO_FEE);
        return doctors.save(doctor);
    }

    private User user(Role role) {
        var user = new User();
        user.setEmail("location-test-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(role);
        return users.save(user);
    }

    private DoctorLocation clinic(Doctor doctor, String name, boolean active) {
        var details = new DoctorLocationService.ClinicDetails(name, "10 Main Street", null, "Central",
                "Kanpur", "Uttar Pradesh", "208001", "5550100", new BigDecimal("25.00"), active);
        return locationService.create(doctor.getId(), details, authentication(doctor.getUser()));
    }

    private List<AppointmentSlot> offers(DoctorLocation clinic, ConsultationMode mode) {
        var request = new DoctorAvailabilityRequest();
        request.setDoctorId(owner.getId());
        request.setDate(LocalDate.now().plusDays(2));
        request.setStartTime(mode == ConsultationMode.VIDEO ? LocalTime.of(11, 0) : LocalTime.of(9, 0));
        request.setEndTime(request.getStartTime().plusHours(1));
        request.setLocationId(clinic == null ? null : clinic.getId());
        request.setConsultationMode(mode);
        var actor = account(owner.getUser());
        var availability = availabilityService.createAvailability(request, actor, false);
        return slotService.generateSlots(availability.getId(), actor, false);
    }

    private static String clinicJson(String name, String fee) {
        return """
                {
                  "clinicName": "%s",
                  "addressLine1": "10 Main Street",
                  "locality": "Central",
                  "city": "Kanpur",
                  "state": "Uttar Pradesh",
                  "postalCode": "208001",
                  "contactPhone": "5550100",
                  "inClinicFee": %s,
                  "active": true
                }
                """.formatted(name, fee);
    }

    private static LocalAccount account(User user) {
        return new LocalAccount(user.getId(), user.getEmail());
    }

    private static Authentication authentication(User user) {
        var principal = new LocalAccount.PasswordPrincipal(account(user), "unused", user.getRole());
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
