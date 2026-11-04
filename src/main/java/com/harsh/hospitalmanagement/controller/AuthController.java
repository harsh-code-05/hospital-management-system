package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.AuthMeResponse;
import com.harsh.hospitalmanagement.dto.LoginRequest;
import com.harsh.hospitalmanagement.dto.LoginResponse;
import com.harsh.hospitalmanagement.security.AuthenticationAudit;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuthenticationAudit audit;
    private final LoginRateLimiter rateLimiter;
    private final SessionAuthenticationStrategy passwordSessionStrategy;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          AuthenticationAudit audit, LoginRateLimiter rateLimiter,
                          SessionAuthenticationStrategy passwordSessionStrategy) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.passwordSessionStrategy = passwordSessionStrategy;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String clientIp = httpRequest.getRemoteAddr();
        long retryAfter = rateLimiter.reserve(clientIp);
        if (retryAfter > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter))
                    .body(Map.of("message", "Too many sign-in attempts. Try again shortly."));
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException ex) {
            audit.rejectedPassword(request.getEmail(), clientIp);
            throw ex;
        }

        LocalAccount account = LocalAccount.require(authentication);
        String role = roleOf(authentication);
        try {
            audit.success(account, role, "password", clientIp);
        } catch (AuthenticationServiceException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", ex.getMessage()));
        }

        passwordSessionStrategy.onAuthentication(authentication, httpRequest, httpResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        SecurityContextHolder.setContext(context);

        return ResponseEntity.ok(new LoginResponse("Login successful", account.email(), role.substring(5)));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LocalAccount.Principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated"));
        }

        LocalAccount account = LocalAccount.require(authentication);
        return ResponseEntity.ok(new AuthMeResponse(account.id(), account.email(), roleOf(authentication)));
    }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    private String roleOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .findFirst()
                .orElseThrow(() -> new AuthenticationServiceException("No local role assigned"));
    }
}
