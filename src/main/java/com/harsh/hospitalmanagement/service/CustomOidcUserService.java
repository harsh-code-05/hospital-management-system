package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
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

    private final OidcUserService delegate;
    private final UserService userService;

    public CustomOidcUserService(UserService userService) {
        this.delegate = new OidcUserService();
        this.userService = userService;
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

        User user = userService.findOrCreateGoogleUser(
                email,
                googleId
        );

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