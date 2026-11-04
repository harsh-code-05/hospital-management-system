package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.security.AuthenticationAudit;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserService userService;
    private final AuthenticationAudit audit;

    @Autowired
    public CustomOidcUserService(UserService userService, AuthenticationAudit audit) {
        this(userService, audit, new OidcUserService());
    }

    public CustomOidcUserService(UserService userService, AuthenticationAudit audit,
                                 OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.userService = userService;
        this.audit = audit;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        if (!"google".equals(userRequest.getClientRegistration().getRegistrationId())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        }
        OidcUser verifiedUser = delegate.loadUser(userRequest);
        String email = verifiedUser.getEmail();
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("missing_email"));
        }

        if (!Boolean.TRUE.equals(verifiedUser.getEmailVerified())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unverified_email"));
        }

        User user;
        try {
            user = userService.findOrCreateGoogleUser(email, verifiedUser.getSubject());
        } catch (BadRequestException | DataIntegrityViolationException ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_link_conflict",
                    "Google sign-in cannot be used for this account", null), ex);
        }

        LocalAccount account = new LocalAccount(user.getId(), user.getEmail());
        try {
            audit.success(account, "ROLE_" + user.getRole().name(), "Google", null);
        } catch (AuthenticationServiceException ex) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("audit_unavailable", "Sign-in temporarily unavailable", null), ex);
        }
        return new LocalAccount.GooglePrincipal(account, user.getRole(), verifiedUser);
    }
}
