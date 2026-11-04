package com.harsh.hospitalmanagement.security;

import com.harsh.hospitalmanagement.enums.Role;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.Serializable;
import java.util.List;

/** The local identity carried by both password and Google sessions. */
public record LocalAccount(long id, String email) implements Serializable {

    public interface Principal {
        LocalAccount account();
    }

    public static LocalAccount require(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Principal principal) {
            return principal.account();
        }
        throw new AuthenticationCredentialsNotFoundException("Sign in again to continue");
    }

    public static final class PasswordPrincipal
            extends org.springframework.security.core.userdetails.User implements Principal {
        private final LocalAccount account;

        public PasswordPrincipal(LocalAccount account, String passwordHash, Role role) {
            super(Long.toString(account.id()), passwordHash,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
            this.account = account;
        }

        @Override
        public LocalAccount account() {
            return account;
        }
    }

    public static final class GooglePrincipal extends DefaultOidcUser implements Principal {
        private final LocalAccount account;

        public GooglePrincipal(LocalAccount account, Role role, OidcUser verifiedUser) {
            super(List.of(new SimpleGrantedAuthority("ROLE_" + role.name())),
                    verifiedUser.getIdToken(), verifiedUser.getUserInfo(), "sub");
            this.account = account;
        }

        @Override
        public LocalAccount account() {
            return account;
        }

        @Override
        public String getName() {
            return Long.toString(account.id());
        }
    }
}
