package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.service.CustomOidcUserService;
import com.harsh.hospitalmanagement.service.CustomUserDetailsService;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.security.LoginRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

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
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SessionAuthenticationStrategy passwordSessionStrategy(CsrfTokenRepository csrfTokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
    }

    @Bean
    public LoginRateLimiter loginRateLimiter() {
        return new LoginRateLimiter();
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
            CsrfTokenRepository csrfTokens,
            CustomOidcUserService customOidcUserService,
            @Value("${app.frontend.url:http://localhost:5173/}") String frontendUrl)
            throws Exception {

        String targetUrl = frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/";
        var apiRequests = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        var browserLogin = new LoginUrlAuthenticationEntryPoint(targetUrl + "login");
        var browserAccessDenied = new AccessDeniedHandlerImpl();
        var browserRequestCache = new HttpSessionRequestCache();
        browserRequestCache.setRequestMatcher(request ->
                HttpMethod.GET.matches(request.getMethod()) && !apiRequests.matches(request));

        http
                .requestCache(cache -> cache.requestCache(browserRequestCache))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                    if (apiRequests.matches(request)) {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setHeader("Cache-Control", "no-store");
                        response.getWriter().write("{\"message\":\"Not authenticated\"}");
                    } else {
                        browserLogin.commence(request, response, failure);
                    }
                }).accessDeniedHandler((request, response, failure) -> {
                    if (apiRequests.matches(request)) {
                        // sendError would redispatch to the protected /error route and redirect anonymous API callers.
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setHeader("Cache-Control", "no-store");
                        response.getWriter().write("{\"status\":403,\"message\":\"Access denied\"}");
                    } else {
                        browserAccessDenied.handle(request, response, failure);
                    }
                }))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                        // Invalidate sessions from before local-ID principals were introduced.
                        if (authentication != null && authentication.isAuthenticated()
                                && !(authentication.getPrincipal() instanceof LocalAccount.Principal)) {
                            SecurityContextHolder.clearContext();
                            var session = request.getSession(false);
                            if (session != null) {
                                session.invalidate();
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class)

                .securityContext(securityContext ->
                        securityContext
                                .securityContextRepository(
                                        securityContextRepository))

                .oauth2Login(oauth2 ->
                        oauth2
                                .loginPage(targetUrl + "login")
                                .userInfoEndpoint(userInfo ->
                                        userInfo
                                                .oidcUserService(
                                                        customOidcUserService))
                                .defaultSuccessUrl(targetUrl, true)
                                .failureUrl(targetUrl + "login?error=google"))

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
                                "/api/auth/me", "/api/auth/csrf"
                        ).permitAll()

                        .requestMatchers("/logout", "/api/auth/logout").denyAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/specializations"
                        ).permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/v2/discovery/**").permitAll()

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
                                "/api/doctors/*/locations",
                                "/api/doctors/*/locations/*",
                                "/api/doctors/*/video-fee"
                        ).hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers("/api/doctors/*/verification").hasAnyRole("DOCTOR", "ADMIN")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctors/*/verification/credentials",
                                "/api/doctors/*/verification/submit"
                        ).hasRole("DOCTOR")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/doctors/*/verification/review",
                                "/api/doctors/*/verification/approve",
                                "/api/doctors/*/verification/reject"
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
                                "/api/patients/me"
                        ).hasRole("PATIENT")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/patients/**"
                        ).hasRole("ADMIN")

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
                                "/api/appointments/**", "/api/v2/appointments"
                        ).hasRole("PATIENT")



                        .requestMatchers("/api/patient/assistant/**").hasRole("PATIENT")

                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().authenticated()
                );

        return http.build();
    }
}
