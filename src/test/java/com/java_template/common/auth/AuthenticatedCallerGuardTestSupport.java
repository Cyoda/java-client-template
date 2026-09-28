package com.java_template.common.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

/** An authenticated JWT user, as Spring Security's resource server puts it on a request thread. */
public final class AuthenticatedCallerGuardTestSupport {

    private AuthenticatedCallerGuardTestSupport() {
    }

    public static JwtAuthenticationToken jwtUser() {
        Jwt jwt = Jwt.withTokenValue("user-jwt").header("alg", "none").claim("sub", "alice").build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
