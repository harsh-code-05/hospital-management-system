package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doc_disabled_storage;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
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
class DoctorVerificationDocumentDisabledStorageTest {

    private static final String UPLOAD_URI = "/api/doctors/{doctorId}/verification/documents/id-proof";

    @Autowired private MockMvc mvc;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationDocumentRepository documents;

    private Doctor doctor;
    private MockHttpSession doctorSession;
    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        validPngBytes = createPngBytes();

        var spec = new Specialization();
        spec.setName("spec-" + UUID.randomUUID());
        spec = specializations.save(spec);

        var user = new User();
        user.setEmail("doc-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(Role.DOCTOR);
        user = users.save(user);

        doctor = new Doctor();
        doctor.setUser(user);
        doctor.setSpecialization(spec);
        doctor = doctors.save(doctor);

        doctorSession = session(user);
    }

    @Test
    void uploadWhenStorageDisabledReturns503() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(UPLOAD_URI, doctor.getId())
                        .file(file)
                        .session(doctorSession)
                        .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Document storage service is temporarily unavailable"));

        // Verify no catalog record created
        assertThat(documents.findByDoctorIdOrderByCreatedAtDesc(doctor.getId())).isEmpty();

        // Verify doctor record unchanged
        Doctor current = doctors.findById(doctor.getId()).orElseThrow();
        assertThat(current.getIdProofDocumentPath()).isNull();
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
