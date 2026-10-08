package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder);

        return provider;
    }
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {

        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CustomOidcUserService customOidcUserService)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .securityContext(securityContext ->
                        securityContext
                                .securityContextRepository(
                                        securityContextRepository))

                .oauth2Login(oauth2 ->
                        oauth2
                                .userInfoEndpoint(userInfo ->
                                        userInfo
                                                .oidcUserService(
                                                        customOidcUserService)))

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/oauth2/**",
                                "/login/**"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/users"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/me"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/logout"
                        ).authenticated()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/specializations"
                        ).permitAll()

                        // Doctor application: public registration (unauthenticated)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctor-applications/register"
                        ).permitAll()

                        // Doctor application: admin-only review endpoints
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctor-applications/pending"
                        ).hasRole("ADMIN")

                        // Doctor application: authenticated applicant routes (must be before /* wildcard)
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctor-applications/me"
                        ).authenticated()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/doctor-applications/*"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/doctor-applications/*/approve"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/doctor-applications/*/reject"
                        ).hasRole("ADMIN")

                        // Doctor application: authenticated applicant POST apply
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctor-applications/apply"
                        ).authenticated()

                        .requestMatchers(
                                "/api/admin/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/specializations/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctors/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/doctors/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/patients/**"
                        ).hasAnyRole("PATIENT", "ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctor-availability/**"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/doctor-availability/**"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/appointment-slots/**"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(HttpMethod.PATCH, "/api/appointments/*/cancel")
                        .hasAnyRole("PATIENT", "DOCTOR", "ADMIN")

                        .requestMatchers(HttpMethod.PATCH, "/api/appointments/*/complete")
                        .hasAnyRole("DOCTOR", "ADMIN")


                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/appointments/**"
                        ).hasRole("PATIENT")



                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().authenticated()
                );

        return http.build();
    }
}