package com.harsh.hospitalmanagement.security;

import com.harsh.hospitalmanagement.enums.Role;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithLocalUser.Factory.class)
public @interface WithLocalUser {
    String username();
    String roles();

    class Factory implements WithSecurityContextFactory<WithLocalUser> {
        public static LocalAccount account(String email) {
            return new LocalAccount(Integer.toUnsignedLong(email.hashCode()) + 1, email);
        }

        @Override
        public SecurityContext createSecurityContext(WithLocalUser annotation) {
            var principal = new LocalAccount.PasswordPrincipal(account(annotation.username()), "unused",
                    Role.valueOf(annotation.roles()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities()));
            return context;
        }
    }
}
