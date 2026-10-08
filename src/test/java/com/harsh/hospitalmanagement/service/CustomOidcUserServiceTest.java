package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomOidcUserServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private OAuth2UserService<OidcUserRequest, OidcUser> delegate;

    @Mock
    private OidcUserRequest userRequest;

    private CustomOidcUserService customOidcUserService;

    @BeforeEach
    void setUp() {
        customOidcUserService = new CustomOidcUserService(userService, delegate);
    }

    private OidcUser createMockOidcUser(String email, String sub, Boolean emailVerified) {
        Map<String, Object> claims = Map.of(
                "sub", sub,
                "email", email,
                "email_verified", emailVerified
        );
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
        OidcUserInfo userInfo = new OidcUserInfo(claims);
        return new DefaultOidcUser(
                Collections.singleton(new SimpleGrantedAuthority("SCOPE_openid")),
                idToken,
                userInfo,
                "email"
        );
    }

    @Test
    void loadUser_shouldReturnOidcUserWithRole_whenEmailVerifiedAndValid() {
        OidcUser mockOidcUser = createMockOidcUser("test@hospital.com", "google-sub-1", true);
        when(delegate.loadUser(userRequest)).thenReturn(mockOidcUser);

        User user = new User();
        user.setEmail("test@hospital.com");
        user.setRole(Role.DOCTOR);
        user.setGoogleId("google-sub-1");
        when(userService.findOrCreateGoogleUser("test@hospital.com", "google-sub-1")).thenReturn(user);

        OidcUser result = customOidcUserService.loadUser(userRequest);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo("test@hospital.com");
        assertThat(result.getAuthorities())
                .extracting("authority")
                .contains("ROLE_DOCTOR");
    }

    @Test
    void loadUser_shouldThrowException_whenEmailIsMissing() {
        Map<String, Object> claims = Map.of("sub", "google-sub-no-email");
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
        OidcUser mockOidcUser = new DefaultOidcUser(
                Collections.singleton(new SimpleGrantedAuthority("SCOPE_openid")),
                idToken,
                "sub"
        );
        when(delegate.loadUser(userRequest)).thenReturn(mockOidcUser);

        assertThatThrownBy(() -> customOidcUserService.loadUser(userRequest))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> {
                    OAuth2AuthenticationException oauthEx = (OAuth2AuthenticationException) ex;
                    assertThat(oauthEx.getError().getErrorCode()).isEqualTo("missing_email");
                });

        verify(userService, never()).findOrCreateGoogleUser(anyString(), anyString());
    }

    @Test
    void loadUser_shouldThrowException_whenEmailIsNotVerified() {
        OidcUser mockOidcUser = createMockOidcUser("unverified@hospital.com", "google-sub-2", false);
        when(delegate.loadUser(userRequest)).thenReturn(mockOidcUser);

        assertThatThrownBy(() -> customOidcUserService.loadUser(userRequest))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> {
                    OAuth2AuthenticationException oauthEx = (OAuth2AuthenticationException) ex;
                    assertThat(oauthEx.getError().getErrorCode()).isEqualTo("unverified_email");
                });

        verify(userService, never()).findOrCreateGoogleUser(anyString(), anyString());
    }

    @Test
    void loadUser_shouldThrowException_whenAccountLinkConflictOccurs() {
        OidcUser mockOidcUser = createMockOidcUser("conflict@hospital.com", "google-sub-3", true);
        when(delegate.loadUser(userRequest)).thenReturn(mockOidcUser);

        when(userService.findOrCreateGoogleUser("conflict@hospital.com", "google-sub-3"))
                .thenThrow(new BadRequestException("Account is already linked to a different Google account"));

        assertThatThrownBy(() -> customOidcUserService.loadUser(userRequest))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> {
                    OAuth2AuthenticationException oauthEx = (OAuth2AuthenticationException) ex;
                    assertThat(oauthEx.getError().getErrorCode()).isEqualTo("account_link_conflict");
                });
    }
}
