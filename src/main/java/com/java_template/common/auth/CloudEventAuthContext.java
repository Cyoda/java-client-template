package com.java_template.common.auth;

import java.util.List;
import java.util.Set;

/**
 * ABOUTME: The principal whose action triggered a callout, as data (spec §4.4). Roles come from the
 * comma-separated authclaims attribute. Trust basis: authclaims can be relied on only over a
 * server-verified TLS channel; with grpc-tls=false it is forgeable (cyoda-go authcontext-attribution.md).
 */
public record CloudEventAuthContext(Type type, String id, List<String> roles) {

    public enum Type { USER, SERVICE, SYSTEM }

    /** The only authtype values cyoda-go sends (service_account is retired). */
    public static final Set<String> AUTH_TYPES = Set.of("user", "service", "system");

    private static final CloudEventAuthContext EMPTY = new CloudEventAuthContext(null, null, List.of());

    public static CloudEventAuthContext empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return type == null;
    }

    /** Mirrors cyoda-go authctx.Require: true only for USER or SERVICE holding exactly this role. */
    public boolean requireRole(String role) {
        return (type == Type.USER || type == Type.SERVICE) && roles.contains(role);
    }
}
