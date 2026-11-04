package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorApplication;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.repository.DoctorApplicationRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2,
        replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(DoctorApplicationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorApplicationConcurrencyTest {

    private static final String WINNING_REASON = "First review reason";
    private static final String CONTENDER_REASON = "Second review reason";

    @Autowired
    private DoctorApplicationService service;

    @Autowired
    private DoctorApplicationRepository applicationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private DoctorRepository doctorRepository;

    @Test
    void approvalRollsBackWhenDoctorProfileSaveFails() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Fixture fixture = transactions.execute(ignored -> createPendingApplication());
        String failureMessage = "Forced Doctor profile save failure";
        doAnswer(invocation -> {
            Doctor profile = invocation.getArgument(0);
            // Flush the promotion so the test requires a database rollback.
            entityManager.flush();
            Long promotedUsers = entityManager.createQuery(
                            "SELECT COUNT(u) FROM User u WHERE u.id = :id AND u.role = :role", Long.class)
                    .setParameter("id", profile.getUser().getId())
                    .setParameter("role", Role.DOCTOR)
                    .getSingleResult();
            assertThat(promotedUsers).isEqualTo(1L);
            throw new DataIntegrityViolationException(failureMessage);
        }).when(doctorRepository).save(any(Doctor.class));

        assertThatThrownBy(() -> service.approveApplication(fixture.applicationId(), fixture.firstAdminId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage(failureMessage);

        ReviewState state = transactions.execute(ignored -> readReviewState(fixture.applicationId()));
        assertThat(state).isEqualTo(new ReviewState(ApplicationStatus.PENDING, Role.PATIENT, 0L, null, null, null));
    }

    @ParameterizedTest(name = "{0} wins against {1}")
    @CsvSource({"APPROVE, REJECT", "REJECT, APPROVE", "APPROVE, APPROVE", "REJECT, REJECT"})
    void onlyFirstDecisionCommits(Decision first, Decision second) throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        Fixture fixture = transactions.execute(ignored -> createPendingApplication());
        CountDownLatch firstDecisionMade = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var winner = executor.submit(() -> transactions.execute(ignored -> {
                DoctorApplication reviewed = decide(first, fixture.applicationId(), fixture.firstAdminId(), WINNING_REASON);
                // The real service has taken its lock. Keep its transaction open.
                firstDecisionMade.countDown();
                await(allowFirstCommit);
                return reviewed.getReviewedAt();
            }));
            await(firstDecisionMade);
            var contender = executor.submit(() -> transactions.execute(ignored -> {
                contenderStarted.countDown();
                return decide(second, fixture.applicationId(), fixture.secondAdminId(), CONTENDER_REASON);
            }));
            await(contenderStarted);
            assertThrows(TimeoutException.class, () -> contender.get(250, TimeUnit.MILLISECONDS),
                    "The second decision must wait for the application lock");

            allowFirstCommit.countDown();
            LocalDateTime winningReviewTime = winner.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> contender.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class)
                    .hasRootCauseMessage(second.alreadyReviewedMessage);

            ReviewState state = transactions.execute(ignored -> readReviewState(fixture.applicationId()));
            boolean approved = first == Decision.APPROVE;
            assertThat(state.status()).isEqualTo(approved ? ApplicationStatus.APPROVED : ApplicationStatus.REJECTED);
            assertThat(state.applicantRole()).isEqualTo(approved ? Role.DOCTOR : Role.PATIENT);
            assertThat(state.doctorProfiles()).isEqualTo(approved ? 1L : 0L);
            assertThat(state.reviewerId()).isEqualTo(fixture.firstAdminId());
            assertThat(state.reviewedAt()).isCloseTo(winningReviewTime, within(1, ChronoUnit.MICROS));
            assertThat(state.rejectionReason()).isEqualTo(approved ? null : WINNING_REASON);

            for (Decision retry : Decision.values()) {
                assertThatThrownBy(() -> decide(retry, fixture.applicationId(), fixture.secondAdminId(), CONTENDER_REASON))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessage(retry.alreadyReviewedMessage);
            }
            ReviewState afterRetries = transactions.execute(ignored -> readReviewState(fixture.applicationId()));
            assertThat(afterRetries).isEqualTo(state);
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private DoctorApplication decide(Decision decision, Long applicationId, Long adminId, String reason) {
        return decision == Decision.APPROVE
                ? service.approveApplication(applicationId, adminId)
                : service.rejectApplication(applicationId, reason, adminId);
    }

    private ReviewState readReviewState(Long applicationId) {
        DoctorApplication application = applicationRepository.findById(applicationId).orElseThrow();
        Long doctorProfiles = entityManager.createQuery(
                        "SELECT COUNT(d) FROM Doctor d WHERE d.user.id = :id", Long.class)
                .setParameter("id", application.getUser().getId())
                .getSingleResult();
        User reviewer = application.getReviewedBy();
        return new ReviewState(application.getStatus(), application.getUser().getRole(), doctorProfiles,
                reviewer == null ? null : reviewer.getId(), application.getReviewedAt(), application.getRejectionReason());
    }

    private Fixture createPendingApplication() {
        String suffix = UUID.randomUUID().toString();
        User applicant = createUser("applicant-" + suffix, Role.PATIENT);
        User firstAdmin = createUser("first-admin-" + suffix, Role.ADMIN);
        User secondAdmin = createUser("second-admin-" + suffix, Role.ADMIN);
        Specialization specialization = new Specialization();
        specialization.setName("Test specialty " + suffix);
        entityManager.persist(specialization);

        DoctorApplication application = new DoctorApplication();
        application.setUser(applicant);
        application.setSpecialization(specialization);
        application.setFirstName("Test");
        application.setLastName("Applicant");
        application.setQualification("Test qualification");
        application.setExperience(2);
        application.setStatus(ApplicationStatus.PENDING);
        application.setCreatedAt(LocalDateTime.now());
        entityManager.persist(application);
        return new Fixture(application.getId(), firstAdmin.getId(), secondAdmin.getId());
    }

    private User createUser(String name, Role role) {
        User user = new User();
        user.setEmail(name + "@test.invalid");
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a review transaction", error);
        }
    }

    private enum Decision {
        APPROVE("Only pending applications can be approved"),
        REJECT("Only pending applications can be rejected");

        private final String alreadyReviewedMessage;

        Decision(String alreadyReviewedMessage) {
            this.alreadyReviewedMessage = alreadyReviewedMessage;
        }
    }

    private record Fixture(Long applicationId, Long firstAdminId, Long secondAdminId) {}

    private record ReviewState(ApplicationStatus status, Role applicantRole, long doctorProfiles,
                               Long reviewerId, LocalDateTime reviewedAt, String rejectionReason) {}
}
