package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.UserRequest;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserService(userRepository, passwordEncoder);
    }

    @Test
    void createUser_shouldEncodePasswordAndSaveUser_whenEmailDoesNotExist() {
        // Arrange
        UserRequest request = new UserRequest();
        request.setEmail("test@example.com");
        request.setPassword("plainTextPassword123");
        request.setRole(Role.PATIENT);

        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        User createdUser = userService.createUser(request);

        // Assert
        verify(userRepository).existsByEmail("test@example.com");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("test@example.com");
        assertThat(savedUser.getRole()).isEqualTo(Role.PATIENT);
        // Verify password is NOT the plaintext password
        assertThat(savedUser.getPassword()).isNotEqualTo("plainTextPassword123");
        // Verify password matches BCrypt format ($2a$...)
        assertThat(savedUser.getPassword()).startsWith("$2a$");
        // Verify password can be verified with BCryptPasswordEncoder
        assertThat(passwordEncoder.matches("plainTextPassword123", savedUser.getPassword())).isTrue();
    }

    @Test
    void createUser_shouldThrowBadRequestException_whenEmailAlreadyExists() {
        // Arrange
        UserRequest request = new UserRequest();
        request.setEmail("duplicate@example.com");
        request.setPassword("password123");
        request.setRole(Role.PATIENT);

        when(userRepository.existsByEmail("duplicate@example.com")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email already exists");

        verify(userRepository).existsByEmail("duplicate@example.com");
        verify(userRepository, never()).save(any(User.class));
    }
    @Test
    void createUser_shouldThrowForbiddenException_whenRoleIsNotPatient() {

        UserRequest request = new UserRequest();
        request.setEmail("admin@example.com");
        request.setPassword("password123");
        request.setRole(Role.ADMIN);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Public registration is allowed only for PATIENT role");

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void findOrCreateGoogleUser_shouldCreateNewPatientUser_whenUserDoesNotExist() {
        String email = "newgoogle@example.com";
        String googleId = "google-sub-123";

        when(userRepository.findByGoogleId(googleId)).thenReturn(java.util.Optional.empty());
        when(userRepository.findByEmail(email)).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = userService.findOrCreateGoogleUser(email, googleId);

        assertThat(user).isNotNull();
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getGoogleId()).isEqualTo(googleId);
        assertThat(user.getRole()).isEqualTo(Role.PATIENT);
        assertThat(user.getPassword()).isNull();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getRole()).isEqualTo(Role.PATIENT);
        assertThat(saved.getGoogleId()).isEqualTo(googleId);
    }

    @Test
    void findOrCreateGoogleUser_shouldReturnExistingUserWithoutChangingRole_whenGoogleIdMatches() {
        String email = "doctor@example.com";
        String googleId = "google-sub-doctor";

        User existing = new User();
        existing.setId(5L);
        existing.setEmail(email);
        existing.setRole(Role.DOCTOR);
        existing.setGoogleId(googleId);

        when(userRepository.findByGoogleId(googleId)).thenReturn(java.util.Optional.of(existing));

        User user = userService.findOrCreateGoogleUser(email, googleId);

        assertThat(user).isSameAs(existing);
        assertThat(user.getRole()).isEqualTo(Role.DOCTOR);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void findOrCreateGoogleUser_shouldLinkGoogleIdPreservingRole_whenExistingUserHasNullGoogleId() {
        String email = "staff@example.com";
        String googleId = "google-sub-link";

        User existing = new User();
        existing.setId(6L);
        existing.setEmail(email);
        existing.setRole(Role.ADMIN);
        existing.setGoogleId(null);

        when(userRepository.findByGoogleId(googleId)).thenReturn(java.util.Optional.empty());
        when(userRepository.findByEmail(email)).thenReturn(java.util.Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = userService.findOrCreateGoogleUser(email, googleId);

        assertThat(user.getGoogleId()).isEqualTo(googleId);
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);

        verify(userRepository).save(existing);
        assertThat(existing.getGoogleId()).isEqualTo(googleId);
        assertThat(existing.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void findOrCreateGoogleUser_shouldThrowBadRequestException_whenExistingUserHasDifferentGoogleId() {
        String email = "conflict@example.com";
        String incomingGoogleId = "google-sub-new";
        String existingGoogleId = "google-sub-existing";

        User existing = new User();
        existing.setId(7L);
        existing.setEmail(email);
        existing.setRole(Role.PATIENT);
        existing.setGoogleId(existingGoogleId);

        when(userRepository.findByGoogleId(incomingGoogleId)).thenReturn(java.util.Optional.empty());
        when(userRepository.findByEmail(email)).thenReturn(java.util.Optional.of(existing));

        assertThatThrownBy(() -> userService.findOrCreateGoogleUser(email, incomingGoogleId))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Account is already linked to a different Google account");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void findOrCreateGoogleUser_shouldThrowBadRequestException_whenEmailOrGoogleIdIsBlank() {
        assertThatThrownBy(() -> userService.findOrCreateGoogleUser("", "google-id"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email is required");

        assertThatThrownBy(() -> userService.findOrCreateGoogleUser("test@example.com", "   "))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Google ID is required");
    }
}
