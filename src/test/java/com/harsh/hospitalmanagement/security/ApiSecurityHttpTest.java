package com.harsh.hospitalmanagement.security;

import com.harsh.hospitalmanagement.ai.AiChatMessageRepository;
import com.harsh.hospitalmanagement.ai.AiChatSessionRepository;
import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import com.harsh.hospitalmanagement.service.DoctorAvailabilityService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Tomcat dispatches and real session login, backed exclusively by isolated H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:api_security_http;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "app.frontend.url=http://localhost:5173/"
})
@Import(ApiSecurityHttpTest.TraceConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiSecurityHttpTest {
    private static final String ROOT = "/api/patient/assistant/sessions";
    private static final String DISCOVERY = "/api/v2/discovery/doctors/";
    private static final String TITLE = "{\"title\":\"Security regression fixture\"}";
    private static final String PASSWORD = "Synthetic-test-password-21";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired SpecializationRepository specializations;
    @Autowired DoctorAvailabilityService availability;
    @Autowired AppointmentSlotService slots;
    @Autowired PatientRepository patients;
    @Autowired AiChatSessionRepository sessions;
    @Autowired AiChatMessageRepository messages;
    @Autowired PasswordEncoder passwords;
    @Autowired DispatchTrace trace;
    private final List<Browser> browsers = new ArrayList<>();

    @BeforeEach
    void resetTrace() { trace.entries.clear(); }

    @AfterEach
    void closeClients() { browsers.forEach(browser -> browser.client.close()); }

    private Browser browser() {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        var browser = new Browser(client, cookies);
        browsers.add(browser);
        return browser;
    }

    private HttpResponse<String> request(Browser browser, String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("X-CSRF-TOKEN", token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return browser.client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrf(Browser browser) throws Exception {
        var response = request(browser, "GET", "/api/auth/csrf", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JSON.readTree(response.body());
        assertThat(json.path("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
        return json.path("token").asString();
    }

    private Browser signedIn(Role role) throws Exception {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@test.invalid");
        user.setRole(role);
        user.setPassword(passwords.encode(PASSWORD));
        users.saveAndFlush(user);
        if (role == Role.PATIENT) {
            Patient patient = new Patient();
            patient.setUser(user);
            patient.setFirstName("Security");
            patient.setLastName("Fixture");
            patients.saveAndFlush(patient);
        }
        var browser = browser();
        String token = csrf(browser);
        String oldSession = sessionId(browser);
        var response = request(browser, "POST", "/api/auth/login",
                "{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}", token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(!oldSession.equals(sessionId(browser))).as("Password login rotates the session ID").isTrue();
        var me = request(browser, "GET", "/api/auth/me", null, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(me.body()).path("id").asLong()).isEqualTo(user.getId());
        return browser;
    }

    private String sessionId(Browser browser) {
        return browser.cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
    }

    private Counts counts() { return new Counts(users.count(), patients.count(), sessions.count(), messages.count()); }

    private void forbidden(HttpResponse<String> response, Counts before) {
        assertThat(response.statusCode()).as("API denial, servlet dispatches: %s", trace.entries).isEqualTo(403);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("message").asString()).isEqualTo("Access denied");
        assertThat(trace.entries).noneMatch(entry -> entry.startsWith("ERROR "));
        assertThat(counts()).as("Rejected requests must not mutate persisted data").isEqualTo(before);
    }

    @Test
    void anonymousPostWithoutExistingSessionAndWithoutCsrfIs403Json() throws Exception {
        var before = counts();
        forbidden(request(browser(), "POST", ROOT, TITLE, null), before);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "invalid"})
    void anonymousSessionWithMissingOrInvalidCsrfIs403Json(String kind) throws Exception {
        var browser = browser();
        csrf(browser);
        var before = counts();
        forbidden(request(browser, "POST", ROOT, TITLE, kind.equals("missing") ? null : "invalid-fixture-token"), before);
    }

    @ParameterizedTest @ValueSource(strings = {"/api/users", "/api/auth/login"})
    void publicApiMutationsStillRequireCsrf(String path) throws Exception {
        var before = counts();
        forbidden(request(browser(), "POST", path, "{}", null), before);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "invalid"})
    void authenticatedSessionWithMissingOrInvalidCsrfIs403Json(String kind) throws Exception {
        var browser = signedIn(Role.PATIENT);
        csrf(browser);
        var before = counts();
        forbidden(request(browser, "POST", ROOT, TITLE, kind.equals("missing") ? null : "invalid-fixture-token"), before);
    }

    @Test
    void validPatientSessionAndCsrfReachRealEndpoint() throws Exception {
        var browser = signedIn(Role.PATIENT);
        var before = counts();
        var response = request(browser, "POST", ROOT, TITLE, csrf(browser));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(response.body()).path("title").asString()).isEqualTo("Security regression fixture");
        assertThat(sessions.count()).isEqualTo(before.sessions + 1);
        assertThat(messages.count()).isEqualTo(before.messages);
        var history = request(browser, "GET", ROOT, null, null);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body()).contains("Security regression fixture");
        assertThat(trace.entries).noneMatch(entry -> entry.startsWith("ERROR "));
    }

    @Test
    void anonymousApiGetAndValidCsrfPostRemain401Json() throws Exception {
        var browser = browser();
        var before = counts();
        for (var response : List.of(request(browser, "GET", ROOT, null, null),
                request(browser, "POST", ROOT, TITLE, csrf(browser)))) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Location")).isEmpty();
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        }
        assertThat(counts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?date=invalid", "?date=", "?date=2099-02-30"})
    void invalidDiscoveryDateReturnsJsonWithoutErrorDispatch(String query) throws Exception {
        // Even a caller asking for HTML must get the same JSON validation response.
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + DISCOVERY + "1/slots" + query))
                .header("Accept", "text/html").timeout(Duration.ofSeconds(10)).GET().build();
        var response = browser().client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("Servlet dispatches: %s", trace.entries).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = JSON.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("message").asString())
                .isEqualTo("Query parameter 'date' is required and must be a valid date in YYYY-MM-DD format.");
        assertThat(trace.entries).noneMatch(entry -> entry.startsWith("ERROR "));
    }

    @Test
    void validDiscoveryDateReturnsAnOfferForAVerifiedDoctor() throws Exception {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@test.invalid");
        user.setRole(Role.DOCTOR);
        user = users.saveAndFlush(user);

        Specialization specialization = new Specialization();
        specialization.setName("Discovery smoke test");
        Doctor doctor = new Doctor();
        doctor.setUser(user);
        doctor.setSpecialization(specializations.saveAndFlush(specialization));
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);
        doctor.setVideoConsultationFee(new BigDecimal("25.00"));
        doctor = doctors.saveAndFlush(doctor);

        var date = LocalDate.now().plusDays(2);
        var window = new DoctorAvailabilityRequest();
        window.setDoctorId(doctor.getId());
        window.setDate(date);
        window.setStartTime(LocalTime.of(9, 0));
        window.setEndTime(LocalTime.of(9, 30));
        window.setConsultationMode(ConsultationMode.VIDEO);
        var actor = new LocalAccount(user.getId(), user.getEmail());
        var persisted = availability.createAvailability(window, actor, false);
        var offer = slots.generateSlots(persisted.getId(), actor, false).getFirst();

        var response = request(browser(), "GET", DISCOVERY + doctor.getId() + "/slots?date=" + date, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = JSON.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).path("slotId").asLong()).isEqualTo(offer.getId());
        assertThat(body.get(0).path("offerVersion").asString()).isEqualTo(offer.getOfferVersion().toString());
    }

    @Test
    void unknownDoctorWithValidDiscoveryDateReturnsAnEmptyArray() throws Exception {
        var response = request(browser(), "GET", DISCOVERY + Long.MAX_VALUE + "/slots?date=2099-01-01", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = JSON.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isZero();
    }

    @Test
    void protectedAppointmentGetStillReturns401Json() throws Exception {
        var response = request(browser(), "GET", "/api/appointments", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(JSON.readTree(response.body()).path("message").asString()).isEqualTo("Not authenticated");
        assertThat(trace.entries).noneMatch(entry -> entry.startsWith("ERROR "));
    }

    @ParameterizedTest @EnumSource(value = Role.class, names = {"DOCTOR", "ADMIN"})
    void validCsrfDoesNotBypassPatientOnlyAuthorization(Role role) throws Exception {
        var browser = signedIn(role);
        var before = counts();
        forbidden(request(browser, "POST", ROOT, TITLE, csrf(browser)), before);
        forbidden(request(browser, "GET", ROOT, null, null), before);
    }

    @ParameterizedTest @ValueSource(strings = {"PATCH", "DELETE"})
    void otherApiMutationMethodsAlsoReturn403JsonWithoutCsrf(String method) throws Exception {
        var browser = signedIn(Role.PATIENT);
        var before = counts();
        forbidden(request(browser, method, ROOT + "/1", null, null), before);
    }

    @Test
    void normalBrowserNavigationAndDirectErrorRouteStillRequireAuthentication() throws Exception {
        for (String path : List.of("/private-page", "/error")) {
            var response = request(browser(), "GET", path, null, null);
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).hasValue("http://localhost:5173/login");
        }
    }

    @Test
    void normalGoogleAuthorizationNavigationStillRedirectsToGoogle() throws Exception {
        var response = request(browser(), "GET", "/oauth2/authorization/google", null, null);
        assertThat(response.statusCode()).isEqualTo(302);
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        assertThat(location.getPath()).isEqualTo("/o/oauth2/v2/auth");
    }

    private record Browser(HttpClient client, CookieManager cookies) { }
    private record Counts(long users, long patients, long sessions, long messages) { }

    static class DispatchTrace {
        final List<String> entries = new CopyOnWriteArrayList<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TraceConfiguration {
        @Bean DispatchTrace dispatchTrace() { return new DispatchTrace(); }

        @Bean
        FilterRegistrationBean<Filter> dispatchTraceFilter(DispatchTrace trace) {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                trace.entries.add(request.getDispatcherType().name() + " " + http.getRequestURI());
                chain.doFilter(request, response);
            });
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
