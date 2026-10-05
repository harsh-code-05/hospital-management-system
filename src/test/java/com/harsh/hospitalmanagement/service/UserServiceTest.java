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
}
