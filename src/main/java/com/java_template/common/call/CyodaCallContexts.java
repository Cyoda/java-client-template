package com.java_template.common.call;

import com.java_template.common.config.Config;
import com.java_template.common.exception.CyodaCalloutEndedException;
import com.java_template.common.exception.CyodaCredentialException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * ABOUTME: The only place a {@link CyodaCallContext} is built (spec §4.2 rules 1–5).
 *
 * <p>{@link #current()} reads Spring Security's thread-local {@code SecurityContext}. Work handed off to
 * another thread from a BFF request — for example an unwrapped {@code CompletableFuture.supplyAsync} —
 * does not carry that thread-local along, so {@code current()} on the new thread sees no authentication
 * and the call goes out as M2M, not forwarding the user's token. Callers that need the user's token
 * forwarded from a background thread must propagate the security context themselves, e.g. by running the
 * work through a {@code org.springframework.security.concurrent.DelegatingSecurityContextExecutor} (or
 * {@code DelegatingSecurityContextCallable}/{@code Runnable}) that copies the context onto the new thread.
 */
@Component
public class CyodaCallContexts {

    private final Config config;

    public CyodaCallContexts(Config config) {
        this.config = config;
    }

    public CyodaCallContext current() {
        CalloutScope scope = CalloutScope.current().orElse(null);
        if (scope != null && !scope.isOpen()) {
            throw new CyodaCalloutEndedException("CALLOUT_SCOPE_CLOSED",
                    "this callout has already answered; its transaction can no longer be joined");
        }
        if (config.getAuthMode() == Config.AuthMode.NONE) {                         // rule 1
            return scope == null ? CyodaCallContext.none() : CyodaCallContext.none().withTxToken(scope.txToken());
        }
        if (scope != null) {                                                         // rule 2
            return CyodaCallContext.m2m().withTxToken(scope.txToken());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {                            // rule 3
            String value = jwt.getToken().getTokenValue();
            if (value == null || value.isBlank()) {
                throw new CyodaCredentialException("the authenticated JWT has a blank token value; refusing to call Cyoda");
            }
            return CyodaCallContext.forward(value);
        }
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) { // rule 4
            throw new CyodaCredentialException("principal of type " + auth.getClass().getSimpleName()
                    + " cannot be forwarded to Cyoda; only a bearer JWT can (spec §4.2)");
        }
        return CyodaCallContext.m2m();                                               // rule 5
    }

    public CyodaCallContext forMemberStream() {
        return config.getAuthMode() == Config.AuthMode.NONE ? CyodaCallContext.none() : CyodaCallContext.m2m();
    }
}
