package com.harsh.hospitalmanagement.security;

import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthenticationManagerTest {

    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;

    @BeforeEach
    void setUp() {

        userRepository = mock(UserRepository.class);

        PasswordEncoder passwordEncoder =
                new BCryptPasswordEncoder();

        CustomUserDetailsService userDetailsService =
                new CustomUserDetailsService(userRepository);

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder);

        authenticationManager =
                new ProviderManager(provider);
    }

    @Test
    void authenticate_shouldSucceed_withCorrectPassword() {

        User user = new User();
        user.setId(1L);
        user.setEmail("patient@gmail.com");
        user.setPassword(
                new BCryptPasswordEncoder()
                        .encode("Test@12345")
        );
        user.setRole(Role.PATIENT);

        when(userRepository.findByEmail("patient@gmail.com"))
                .thenReturn(Optional.of(user));

        UsernamePasswordAuthenticationToken request =
                new UsernamePasswordAuthenticationToken(
                        "patient@gmail.com",
                        "Test@12345"
                );

        var authentication =
                authenticationManager.authenticate(request);

        assertThat(authentication.isAuthenticated())
                .isTrue();

        assertThat(authentication.getName())
                .isEqualTo("patient@gmail.com");

        assertThat(authentication.getAuthorities())
                .anyMatch(authority ->
                        authority.getAuthority()
                                .equals("ROLE_PATIENT"));
    }

    @Test
    void authenticate_shouldFail_withWrongPassword() {

        User user = new User();
        user.setId(1L);
        user.setEmail("patient@gmail.com");
        user.setPassword(
                new BCryptPasswordEncoder()
                        .encode("Test@12345")
        );
        user.setRole(Role.PATIENT);

        when(userRepository.findByEmail("patient@gmail.com"))
                .thenReturn(Optional.of(user));

        UsernamePasswordAuthenticationToken request =
                new UsernamePasswordAuthenticationToken(
                        "patient@gmail.com",
                        "WrongPassword"
                );

        assertThatThrownBy(() ->
                authenticationManager.authenticate(request))
                .isInstanceOf(
                        org.springframework.security.core.AuthenticationException.class);
    }
}