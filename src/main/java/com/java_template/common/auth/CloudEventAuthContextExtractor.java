package com.java_template.common.auth;

import io.cloudevents.v1.proto.CloudEvent;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** ABOUTME: Reads authtype/authid/authclaims from a callout CloudEvent (authclaims is comma-separated only). */
@Component
public class CloudEventAuthContextExtractor {

    public static CloudEventAuthContext from(CloudEvent cloudEvent) {
        var attrs = cloudEvent.getAttributesMap();
        String type = attrs.containsKey("authtype") ? attrs.get("authtype").getCeString() : null;
        if (type == null || !CloudEventAuthContext.AUTH_TYPES.contains(type)) {
            return CloudEventAuthContext.empty();
        }
        String id = attrs.containsKey("authid") ? attrs.get("authid").getCeString() : null;
        String claims = attrs.containsKey("authclaims") ? attrs.get("authclaims").getCeString() : "";
        return new CloudEventAuthContext(CloudEventAuthContext.Type.valueOf(type.toUpperCase(Locale.ROOT)), id, parseRoles(claims));
    }

    static List<String> parseRoles(String claims) {
        if (claims == null || claims.isBlank() || claims.trim().startsWith("{")) {
            return List.of();
        }
        return Arrays.stream(claims.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Kept for existing callers; prefer {@link #from(CloudEvent)} or CyodaEventContext.authContext(). */
    public Optional<CloudEventAuthContext> extract(CloudEvent cloudEvent) {
        CloudEventAuthContext ctx = from(cloudEvent);
        return ctx.isEmpty() ? Optional.empty() : Optional.of(ctx);
    }
}
