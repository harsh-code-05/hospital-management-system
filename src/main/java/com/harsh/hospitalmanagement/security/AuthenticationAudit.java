package com.harsh.hospitalmanagement.security;

import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;

@Component
public final class AuthenticationAudit {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationAudit.class);

    private final AuditLogService auditLogService;

    public AuthenticationAudit(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    // Successful authentication must be audited before a security context is persisted.
    public void success(LocalAccount account, String role, String method, String source) {
        try {
            auditLogService.logEvent(AuditEventType.LOGIN_SUCCESS, account.email(), role, "User",
                    account.id(), "User logged in successfully via " + method, source);
        } catch (RuntimeException ex) {
            LOGGER.error("Authentication audit write failed ({})", ex.getClass().getSimpleName());
            throw new AuthenticationServiceException("Sign-in temporarily unavailable", ex);
        }
    }

    // A failure remains a rejection even if the audit store is unavailable.
    public void rejectedPassword(String submittedEmail, String source) {
        try {
            auditLogService.logEvent(AuditEventType.LOGIN_FAILURE, submittedEmail, null, "User",
                    null, "Failed login attempt", source);
        } catch (RuntimeException ex) {
            LOGGER.error("Rejected password attempt could not be audited ({})", ex.getClass().getSimpleName());
        }
    }
}
