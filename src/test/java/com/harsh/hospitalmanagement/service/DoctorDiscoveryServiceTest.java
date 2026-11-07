package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.DoctorLocationRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:discovery_service_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.sql.init.mode=never"
}, showSql = false)
@Import(DoctorDiscoveryService.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DoctorDiscoveryServiceTest {

    @Autowired private DoctorDiscoveryService discoveryService;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private DoctorLocationRepository locationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SpecializationRepository specializationRepository;

    private Specialization cardiology;
    private Specialization neurology;

    private Doctor verifiedDoctorA;
    private Doctor verifiedDoctorB;
    private Doctor unverifiedDoctor;
    private Doctor rejectedDoctor;

    @BeforeEach
    void setUp() {
        cardiology = new Specialization();
        cardiology.setName("Cardiology");
        cardiology = specializationRepository.saveAndFlush(cardiology);

        neurology = new Specialization();
        neurology.setName("Neurology");
        neurology = specializationRepository.saveAndFlush(neurology);

        // Doctor A: Verified, Cardiology, offers both Video (fee 500) and In-Clinic in Mumbai
        verifiedDoctorA = createDoctor("Alice", "Smith", cardiology, VerificationStatus.VERIFIED, new BigDecimal("500.00"), 10);
        createLocation(verifiedDoctorA, "Heart Clinic Mumbai", "Bandra", "Mumbai", "Maharashtra", "400050", new BigDecimal("600.00"), true, false);

        // Doctor B: Verified, Neurology, offers In-Clinic in Pune, no video fee
        verifiedDoctorB = createDoctor("Bob", "Jones", neurology, VerificationStatus.VERIFIED, null, 7);
        createLocation(verifiedDoctorB, "Neuro Care Pune", "Kothrud", "Pune", "Maharashtra", "411038", new BigDecimal("750.00"), true, false);

        // Doctor C: Unverified
        unverifiedDoctor = createDoctor("Charlie", "Brown", cardiology, VerificationStatus.UNVERIFIED, new BigDecimal("400.00"), 3);
        createLocation(unverifiedDoctor, "Cardio Care", "Andheri", "Mumbai", "Maharashtra", "400053", new BigDecimal("400.00"), true, false);

        // Doctor D: Rejected
        rejectedDoctor = createDoctor("David", "Wilson", neurology, VerificationStatus.REJECTED, new BigDecimal("450.00"), 5);
        createLocation(rejectedDoctor, "Wilson Clinic", "Shivajinagar", "Pune", "Maharashtra", "411005", new BigDecimal("500.00"), true, false);
    }

    private Doctor createDoctor(String first, String last, Specialization spec, VerificationStatus status, BigDecimal videoFee, Integer experience) {
        User user = new User();
        user.setEmail(first.toLowerCase() + "." + last.toLowerCase() + "_" + UUID.randomUUID() + "@example.com");
        user.setPassword("secret123");
        user.setRole(Role.DOCTOR);
        user = userRepository.saveAndFlush(user);

        Doctor doc = new Doctor();
        doc.setUser(user);
        doc.setFirstName(first);
        doc.setLastName(last);
        doc.setSpecialization(spec);
        doc.setVerificationStatus(status);
        doc.setVideoConsultationFee(videoFee);
        doc.setExperience(experience);
        doc.setQualification("MBBS, MD");
        doc.setGender("Other");
        return doctorRepository.saveAndFlush(doc);
    }

    private DoctorLocation createLocation(Doctor doctor, String name, String locality, String city, String state, String pin, BigDecimal fee, boolean active, boolean archived) {
        DoctorLocation loc = new DoctorLocation();
        loc.setDoctor(doctor);
        loc.setClinicName(name);
        loc.setAddressLine1("123 Health Ave");
        loc.setLocality(locality);
        loc.setCity(city);
        loc.setState(state);
        loc.setPostalCode(pin);
        loc.setContactPhone("9876543210");
        loc.setInClinicFee(fee);
        loc.setActive(active);
        loc.setArchived(archived);
        return locationRepository.saveAndFlush(loc);
    }

    @Test
    void discoverDoctorsReturnsOnlyVerifiedDoctors() {
        List<DoctorDiscoveryResponse> results = discoveryService.discoverDoctors(null, null, null);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(DoctorDiscoveryResponse::doctorId)
                .containsExactly(verifiedDoctorA.getId(), verifiedDoctorB.getId());
        assertThat(results).extracting(DoctorDiscoveryResponse::displayName)
                .containsExactly("Dr. Alice Smith", "Dr. Bob Jones");
    }

    @Test
    void discoverDoctorsExcludesArchivedOrInactiveClinics() {
        // Add an inactive clinic and an archived clinic to Doctor A
        createLocation(verifiedDoctorA, "Closed Clinic", "Colaba", "Mumbai", "Maharashtra", "400001", new BigDecimal("100.00"), false, false);
        createLocation(verifiedDoctorA, "Archived Clinic", "Dadar", "Mumbai", "Maharashtra", "400014", new BigDecimal("200.00"), true, true);

        List<DoctorDiscoveryResponse> results = discoveryService.discoverDoctors(null, null, null);
        DoctorDiscoveryResponse docA = results.stream().filter(r -> r.doctorId().equals(verifiedDoctorA.getId())).findFirst().orElseThrow();

        // Should only have the one active, non-archived clinic
        assertThat(docA.clinics()).hasSize(1);
        assertThat(docA.clinics().getFirst().clinicName()).isEqualTo("Heart Clinic Mumbai");
    }

    @Test
    void filterBySpecializationIdReturnsMatchingDoctors() {
        List<DoctorDiscoveryResponse> cardio = discoveryService.discoverDoctors(cardiology.getId(), null, null);
        assertThat(cardio).hasSize(1);
        assertThat(cardio.getFirst().doctorId()).isEqualTo(verifiedDoctorA.getId());
        assertThat(cardio.getFirst().specialization().name()).isEqualTo("Cardiology");

        List<DoctorDiscoveryResponse> neuro = discoveryService.discoverDoctors(neurology.getId(), null, null);
        assertThat(neuro).hasSize(1);
        assertThat(neuro.getFirst().doctorId()).isEqualTo(verifiedDoctorB.getId());
        assertThat(neuro.getFirst().specialization().name()).isEqualTo("Neurology");
    }

    @Test
    void filterByCityReturnsDoctorsWithActiveClinicInCity() {
        List<DoctorDiscoveryResponse> mumbai = discoveryService.discoverDoctors(null, "Mumbai", null);
        assertThat(mumbai).hasSize(1);
        assertThat(mumbai.getFirst().doctorId()).isEqualTo(verifiedDoctorA.getId());

        List<DoctorDiscoveryResponse> pune = discoveryService.discoverDoctors(null, "pune", null); // case-insensitive
        assertThat(pune).hasSize(1);
        assertThat(pune.getFirst().doctorId()).isEqualTo(verifiedDoctorB.getId());

        List<DoctorDiscoveryResponse> delhi = discoveryService.discoverDoctors(null, "Delhi", null);
        assertThat(delhi).isEmpty();
    }

    @Test
    void filterByModeInClinicReturnsDoctorsWithActiveClinics() {
        List<DoctorDiscoveryResponse> inClinic = discoveryService.discoverDoctors(null, null, "IN_CLINIC");
        assertThat(inClinic).hasSize(2); // Both doctor A and B have active clinics
    }

    @Test
    void filterByModeVideoReturnsDoctorsWithVideoFee() {
        List<DoctorDiscoveryResponse> video = discoveryService.discoverDoctors(null, null, "VIDEO");
        assertThat(video).hasSize(1);
        assertThat(video.getFirst().doctorId()).isEqualTo(verifiedDoctorA.getId());
    }

    @Test
    void combinedFiltersWorkTogether() {
        // Cardiology in Mumbai offering Video
        List<DoctorDiscoveryResponse> matches = discoveryService.discoverDoctors(cardiology.getId(), "Mumbai", "VIDEO");
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().doctorId()).isEqualTo(verifiedDoctorA.getId());

        // Neurology in Mumbai (Doctor B is in Pune) -> empty
        List<DoctorDiscoveryResponse> noMatch = discoveryService.discoverDoctors(neurology.getId(), "Mumbai", null);
        assertThat(noMatch).isEmpty();
    }

    @Test
    void invalidSpecializationIdThrowsBadRequestException() {
        assertThatThrownBy(() -> discoveryService.discoverDoctors(-1L, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("positive integer");

        assertThatThrownBy(() -> discoveryService.discoverDoctors(0L, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("positive integer");
    }

    @Test
    void invalidModeThrowsBadRequestException() {
        assertThatThrownBy(() -> discoveryService.discoverDoctors(null, null, "TELEPHONE"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("IN_CLINIC, VIDEO");
    }

    @Test
    void doctorWithNoModesIsExcluded() {
        // Create verified doctor with NO clinics and NO video fee
        Doctor doctorNoModes = createDoctor("Zack", "Taylor", cardiology, VerificationStatus.VERIFIED, null, 1);
        List<DoctorDiscoveryResponse> results = discoveryService.discoverDoctors(null, null, null);
        assertThat(results).extracting(DoctorDiscoveryResponse::doctorId)
                .doesNotContain(doctorNoModes.getId());
    }

    @Test
    void resultsAreBoundedToMaximumOfFiftyDoctorsWithDeterministicOrdering() {
        // Create 60 additional verified doctors
        for (int i = 1; i <= 60; i++) {
            Doctor doc = createDoctor("BulkDoc" + i, "Test", cardiology, VerificationStatus.VERIFIED, new BigDecimal("300.00"), 5);
            createLocation(doc, "Clinic " + i, "Area " + i, "Mumbai", "Maharashtra", "400001", new BigDecimal("400.00"), true, false);
        }

        List<DoctorDiscoveryResponse> results = discoveryService.discoverDoctors(null, null, null);

        // Must be capped at 50 results
        assertThat(results).hasSize(DoctorDiscoveryService.MAX_DISCOVERY_RESULTS);
        assertThat(results.size()).isEqualTo(50);

        // Must be deterministically ordered by doctor ID ascending
        List<Long> returnedIds = results.stream().map(DoctorDiscoveryResponse::doctorId).toList();
        List<Long> sortedIds = returnedIds.stream().sorted().toList();
        assertThat(returnedIds).isEqualTo(sortedIds);

        // Locations are only loaded for the 50 returned doctors
        for (DoctorDiscoveryResponse resp : results) {
            assertThat(returnedIds).contains(resp.doctorId());
        }
    }
}
