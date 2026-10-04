package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private CustomUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new CustomUserDetailsService(userRepository);
    }

    @Test
    void loadUserByUsername_shouldReturnUserDetails_whenUserExists() {

        User user = new User();
        user.setId(1L);
        user.setEmail("patient@gmail.com");
        user.setPassword("$2a$10$examplehashedpassword");
        user.setRole(Role.PATIENT);

        when(userRepository.findByEmail("patient@gmail.com"))
                .thenReturn(Optional.of(user));

        UserDetails userDetails =
                userDetailsService.loadUserByUsername("patient@gmail.com");

        assertThat(userDetails.getUsername())
                .isEqualTo("patient@gmail.com");

        assertThat(userDetails.getPassword())
                .isEqualTo("$2a$10$examplehashedpassword");

        assertThat(userDetails.getAuthorities())
                .anyMatch(authority ->
                        authority.getAuthority().equals("ROLE_PATIENT"));

        verify(userRepository).findByEmail("patient@gmail.com");
    }

    @Test
    void loadUserByUsername_shouldThrowException_whenUserDoesNotExist() {

        when(userRepository.findByEmail("unknown@gmail.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                userDetailsService.loadUserByUsername("unknown@gmail.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found: unknown@gmail.com");

        verify(userRepository).findByEmail("unknown@gmail.com");
    }
}