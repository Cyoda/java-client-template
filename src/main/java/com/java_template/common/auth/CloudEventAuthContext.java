package com.java_template.common.auth;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable value object holding auth context attributes from a Cyoda CloudEvent.
 * Populated from the CloudEvents Auth Context Extension (authtype/authid/authclaims).
 */
public record CloudEventAuthContext(
        String authType,
        @Nullable String authId,
        @Nullable String authClaimsJson
) {
    /** The only authtype values cyoda-go sends (service_account is retired). */
    public static final java.util.Set<String> AUTH_TYPES = java.util.Set.of("user", "service", "system");

    public boolean isUserContext() {
        return "user".equals(authType);
    }
}
