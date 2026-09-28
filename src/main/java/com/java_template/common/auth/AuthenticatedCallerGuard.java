package com.java_template.common.auth;

import com.java_template.common.exception.CyodaCredentialException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ABOUTME: Fail-closed check before the M2M token is attached to a Cyoda call: a call made on behalf of an
 * authenticated user must never silently run with the service account's rights.
 * <p>
 * The template has no way yet to forward a user's own token to Cyoda, so while the current thread's
 * SecurityContext holds a non-anonymous, authenticated {@link Authentication} the call is refused. Anonymous
 * requests, threads without a SecurityContext (processor and criterion callouts, startup tasks) and
 * {@code auth-mode=none} are unaffected.
 */
public final class AuthenticatedCallerGuard {

    private AuthenticatedCallerGuard() {
    }

    /**
     * @throws CyodaCredentialException when the current thread carries an authenticated user
     */
    public static void refuseM2mForAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }
        throw new CyodaCredentialException("This Cyoda call was made for an authenticated user ("
                + authentication.getClass().getSimpleName() + "). Forwarding a user's token to Cyoda arrives with "
                + "the call-context model; until then the call is refused, so it does not run with the service "
                + "account's (M2M) rights. Make service-level Cyoda calls outside the user's request thread.");
    }
}
