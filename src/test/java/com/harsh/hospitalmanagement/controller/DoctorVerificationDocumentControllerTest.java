package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doc_verification_api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never",
        "spring.servlet.multipart.max-file-size=5MB",
        "spring.servlet.multipart.max-request-size=11MB"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationDocumentControllerTest {

    private static final String BASE_URI = "/api/doctors/{doctorId}/verification/documents";
    private static final String KIND_URI = BASE_URI + "/{kind}";
    private static final String HISTORY_URI = KIND_URI + "/history";

    @Autowired private MockMvc mvc;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository events;

    @MockitoBean private DocumentStorage documentStorage;

    private Doctor doctor1;
    private Doctor doctor2;
    private User adminUser;
    private User patientUser;

    private MockHttpSession doctor1Session;
    private MockHttpSession doctor2Session;
    private MockHttpSession adminSession;
    private MockHttpSession patientSession;

    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        validPngBytes = createPngBytes();

        doctor1 = createDoctor();
        doctor2 = createDoctor();
        adminUser = createUser(Role.ADMIN);
        patientUser = createUser(Role.PATIENT);

        doctor1Session = session(doctor1.getUser());
        doctor2Session = session(doctor2.getUser());
        adminSession = session(adminUser);
        patientSession = session(patientUser);
    }

    @Test
    void unauthenticatedCallersReceive401Json() throws Exception {
        mvc.perform(get(BASE_URI, doctor1.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not authenticated"));

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(new MockMultipartFile("file", "test.png", "image/png", validPngBytes))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientCallersReceive403Forbidden() throws Exception {
        mvc.perform(get(BASE_URI, doctor1.getId()).session(patientSession))
                .andExpect(status().isForbidden());

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(new MockMultipartFile("file", "test.png", "image/png", validPngBytes))
                        .session(patientSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCanUploadOwnVerificationDocument() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .header("X-Correlation-ID", "trace-upload-123")
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.doctorId").value(doctor1.getId()))
                .andExpect(jsonPath("$.documentKind").value("ID_PROOF"))
                .andExpect(jsonPath("$.mediaType").value("image/png"))
                .andExpect(jsonPath("$.fileSize").isNumber())
                .andExpect(jsonPath("$.checksum").isString())
                .andExpect(jsonPath("$.retiredAt").doesNotExist());

        Doctor updated = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(updated.getIdProofDocumentPath()).isEqualTo(expectedKey.toString());
    }

    @Test
    void doctorCannotUploadAnotherDoctorsVerificationDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor2.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotUploadWhenStatusIsNotDraft() throws Exception {
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctors.save(doctor1);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void doctorCanDetachOwnDocument() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isCreated());

        mvc.perform(delete(KIND_URI, doctor1.getId(), "id-proof")
                        .header("X-Correlation-ID", "detach-trace-456")
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        Doctor updated = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(updated.getIdProofDocumentPath()).isNull();
    }

    @Test
    void doctorCannotDetachAnotherDoctorsDocument() throws Exception {
        mvc.perform(delete(KIND_URI, doctor2.getId(), "id-proof")
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotReadRawDocumentBytes() throws Exception {
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof").session(doctor1Session))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadRawDocumentBytesWhenDoctorIsUnderReview() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);
        when(documentStorage.read(eq(expectedKey)))
                .thenReturn(new DocumentStorage.ReadDocument("image/png", "id-proof.png", validPngBytes));

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isCreated());

        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctors.save(doctor1);

        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, private, max-age=0"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"doctor-" + doctor1.getId() + "-id-proof.png\""))
                .andExpect(content().bytes(validPngBytes));
    }

    @Test
    void adminCannotReadRawDocumentBytesWhenDoctorIsNotUnderReview() throws Exception {
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof").session(adminSession))
                .andExpect(status().isConflict());
    }

    @Test
    void doctorAndAdminCanListActiveDocuments() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isCreated());

        mvc.perform(get(BASE_URI, doctor1.getId()).session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentKind").value("ID_PROOF"));

        mvc.perform(get(BASE_URI, doctor1.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void doctorAndAdminCanViewDocumentHistory() throws Exception {
        mvc.perform(get(HISTORY_URI, doctor1.getId(), "id-proof").session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get(HISTORY_URI, doctor1.getId(), "id-proof").session(adminSession))
                .andExpect(status().isOk());
    }

    @Test
    void doctorCannotListAnotherDoctorsDocuments() throws Exception {
        mvc.perform(get(BASE_URI, doctor2.getId()).session(doctor1Session))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotViewAnotherDoctorsDocumentHistory() throws Exception {
        mvc.perform(get(HISTORY_URI, doctor2.getId(), "id-proof").session(doctor1Session))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestExceedingMaxRequestSizeReturns413() throws Exception {
        byte[] chunk1 = new byte[4 * 1024 * 1024];
        byte[] chunk2 = new byte[4 * 1024 * 1024];
        byte[] chunk3 = new byte[4 * 1024 * 1024]; // 12MB total > 11MB
        MockMultipartFile file1 = new MockMultipartFile("file", "part1.png", "image/png", chunk1);
        MockMultipartFile file2 = new MockMultipartFile("extra1", "part2.png", "image/png", chunk2);
        MockMultipartFile file3 = new MockMultipartFile("extra2", "part3.png", "image/png", chunk3);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file1)
                        .file(file2)
                        .file(file3)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void oversizedUploadReturns413PayloadTooLarge() throws Exception {
        byte[] oversized = new byte[VerificationImageValidator.MAX_FILE_BYTES + 10];
        MockMultipartFile file = new MockMultipartFile("file", "too-large.png", "image/png", oversized);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void invalidDoctorIdReturns400() throws Exception {
        mvc.perform(get(BASE_URI, -5).session(adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Doctor ID must be a positive integer"));
    }

    @Test
    void invalidDocumentKindReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "passport")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification document kind: passport"));
    }

    @Test
    void unsupportedMediaTypeReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only PNG and JPEG images are supported for verification documents"));
    }

    @Test
    void missingMultipartFileReturns400() throws Exception {
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private Doctor createDoctor() {
        var spec = new Specialization();
        spec.setName("spec-" + UUID.randomUUID());
        spec = specializations.save(spec);

        var user = createUser(Role.DOCTOR);
        var doc = new Doctor();
        doc.setUser(user);
        doc.setSpecialization(spec);
        return doctors.save(doc);
    }

    private User createUser(Role role) {
        var user = new User();
        user.setEmail("user-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(role);
        return users.save(user);
    }

    private static byte[] createPngBytes() throws IOException {
        var image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 3, Color.BLUE.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private static Authentication authentication(User user) {
        var principal = new LocalAccount.PasswordPrincipal(
                new LocalAccount(user.getId(), user.getEmail()), "unused", user.getRole());
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
