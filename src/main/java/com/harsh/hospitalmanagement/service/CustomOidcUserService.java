package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class CustomOidcUserService
        implements org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
        OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserService userService;

    @Autowired
    public CustomOidcUserService(UserService userService) {
        this(userService, new OidcUserService());
    }

    public CustomOidcUserService(
            UserService userService,
            OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.userService = userService;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest)
            throws OAuth2AuthenticationException {

        OidcUser oidcUser = delegate.loadUser(userRequest);

        String email = oidcUser.getEmail();
        String googleId = oidcUser.getSubject();

        if (email == null || email.isBlank()) {
            OAuth2Error error = new OAuth2Error(
                    "missing_email",
                    "Google account email is required",
                    null
            );

            throw new OAuth2AuthenticationException(error);
        }

        Boolean emailVerified = oidcUser.getEmailVerified();
        if (emailVerified == null && oidcUser.getUserInfo() != null) {
            emailVerified = oidcUser.getUserInfo().getEmailVerified();
        }
        if (Boolean.FALSE.equals(emailVerified)) {
            OAuth2Error error = new OAuth2Error(
                    "unverified_email",
                    "Google email is not verified",
                    null
            );

            throw new OAuth2AuthenticationException(error);
        }

        User user;
        try {
            user = userService.findOrCreateGoogleUser(
                    email,
                    googleId
            );
        } catch (BadRequestException ex) {
            OAuth2Error error = new OAuth2Error(
                    "account_link_conflict",
                    ex.getMessage(),
                    null
            );

            throw new OAuth2AuthenticationException(error, ex);
        }

        Set<GrantedAuthority> authorities =
                new HashSet<>(oidcUser.getAuthorities());

        authorities.add(
                new SimpleGrantedAuthority(
                        "ROLE_" + user.getRole().name()
                )
        );

        return new DefaultOidcUser(
                authorities,
                oidcUser.getIdToken(),
                oidcUser.getUserInfo(),
                "email"
        );
    }
}