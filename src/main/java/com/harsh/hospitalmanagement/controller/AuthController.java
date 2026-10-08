package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.AuthMeResponse;
import com.harsh.hospitalmanagement.dto.LoginRequest;
import com.harsh.hospitalmanagement.dto.LoginResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.service.AuditLogService;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuditLogService auditLogService;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            AuditLogService auditLogService) {

        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String clientIp = httpRequest.getRemoteAddr();

        try {
            Authentication authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    request.getEmail(),
                                    request.getPassword()
                            )
                    );

            SecurityContext securityContext =
                    SecurityContextHolder.createEmptyContext();

            securityContext.setAuthentication(authentication);

            SecurityContextHolder.setContext(securityContext);

            securityContextRepository.saveContext(
                    securityContext,
                    httpRequest,
                    httpResponse
            );

            String role = authentication.getAuthorities()
                    .stream()
                    .findFirst()
                    .map(authority ->
                            authority.getAuthority().replace("ROLE_", ""))
                    .orElse("");

            auditLogService.logEvent(
                    AuditEventType.LOGIN_SUCCESS,
                    authentication.getName(),
                    "ROLE_" + role,
                    "User",
                    null,
                    "User logged in successfully",
                    clientIp
            );

            LoginResponse response = new LoginResponse(
                    "Login successful",
                    authentication.getName(),
                    role
            );

            return ResponseEntity.ok(response);
        } catch (AuthenticationException ex) {
            auditLogService.logEvent(
                    AuditEventType.LOGIN_FAILURE,
                    request.getEmail(),
                    null,
                    "User",
                    null,
                    "Failed login attempt: " + ex.getMessage(),
                    clientIp
            );
            throw ex;
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated"));
        }

        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .orElse("ROLE_PATIENT");

        AuthMeResponse response = new AuthMeResponse(
                authentication.getName(),
                role
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        SecurityContextHolder.clearContext();

        Cookie cookie = new Cookie("JSESSIONID", null);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}