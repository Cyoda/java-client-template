package com.java_template.common.auth;

import com.java_template.common.exception.CyodaCredentialException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedCallerGuardTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAuthenticatedJwtUserIsRefused() {
        SecurityContextHolder.getContext().setAuthentication(AuthenticatedCallerGuardTestSupport.jwtUser());

        assertThatThrownBy(AuthenticatedCallerGuard::refuseM2mForAuthenticatedUser)
                .isInstanceOf(CyodaCredentialException.class)
                .hasMessageContaining("refused")
                .hasMessageContaining("call-context model")
                .hasMessageNotContaining("user-jwt");
    }

    @Test
    void anAuthenticatedUsernameUserIsRefused() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", "pw", List.of()));

        assertThatThrownBy(AuthenticatedCallerGuard::refuseM2mForAuthenticatedUser)
                .isInstanceOf(CyodaCredentialException.class);
    }

    @Test
    void anAnonymousUserIsNotRefused() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatCode(AuthenticatedCallerGuard::refuseM2mForAuthenticatedUser).doesNotThrowAnyException();
    }

    @Test
    void anUnauthenticatedTokenIsNotRefused() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "pw"));

        assertThatCode(AuthenticatedCallerGuard::refuseM2mForAuthenticatedUser).doesNotThrowAnyException();
    }

    @Test
    void noAuthenticationIsNotRefused() {
        assertThatCode(AuthenticatedCallerGuard::refuseM2mForAuthenticatedUser).doesNotThrowAnyException();
    }
}
