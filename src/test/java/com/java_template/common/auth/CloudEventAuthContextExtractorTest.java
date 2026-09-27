package com.java_template.common.auth;

import io.cloudevents.v1.proto.CloudEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventAuthContextExtractorTest {

    private static CloudEvent.CloudEventAttributeValue str(String v) {
        return CloudEvent.CloudEventAttributeValue.newBuilder().setCeString(v).build();
    }

    private static CloudEvent event(String type, String id, String claims) {
        CloudEvent.Builder b = CloudEvent.newBuilder().setId("e").setSource("s").setSpecVersion("1.0").setType("t");
        if (type != null) b.putAttributes("authtype", str(type));
        if (id != null) b.putAttributes("authid", str(id));
        if (claims != null) b.putAttributes("authclaims", str(claims));
        return b.build();
    }

    @Test
    void parsesTheCommaSeparatedWireForm() {
        CloudEventAuthContext ctx = CloudEventAuthContextExtractor.from(event("user", "mock-user-001", "ROLE_ADMIN,ROLE_M2M"));

        assertThat(ctx.type()).isEqualTo(CloudEventAuthContext.Type.USER);
        assertThat(ctx.id()).isEqualTo("mock-user-001");
        assertThat(ctx.roles()).containsExactly("ROLE_ADMIN", "ROLE_M2M");
    }

    @Test
    void trimsAndDropsBlanks() {
        assertThat(CloudEventAuthContextExtractor.from(event("service", "c", " a , ,b ")).roles()).containsExactly("a", "b");
        assertThat(CloudEventAuthContextExtractor.from(event("service", "c", "")).roles()).isEmpty();
    }

    @Test
    void aJsonObjectIsNotRoles() {
        CloudEventAuthContext ctx = CloudEventAuthContextExtractor.from(
                event("user", "u", "{\"legalEntityId\":\"org-1\",\"roles\":[\"USER\"]}"));

        assertThat(ctx.roles()).isEmpty();
        assertThat(ctx.requireRole("USER")).isFalse();
    }

    @Test
    void unknownRetiredOrAbsentAuthTypeYieldsAnEmptyContext() {
        assertThat(CloudEventAuthContextExtractor.from(event("service_account", "x", "ROLE_M2M")).isEmpty()).isTrue();
        assertThat(CloudEventAuthContextExtractor.from(event(null, null, null)).isEmpty()).isTrue();
    }

    @Test
    void requireRoleIsFailClosedAndExact() {
        CloudEventAuthContext user = CloudEventAuthContextExtractor.from(event("user", "u", "ROLE_ADMIN"));
        CloudEventAuthContext service = CloudEventAuthContextExtractor.from(event("service", "c", "ROLE_M2M"));
        CloudEventAuthContext system = CloudEventAuthContextExtractor.from(event("system", null, "ROLE_ADMIN"));

        assertThat(user.requireRole("ROLE_ADMIN")).isTrue();
        assertThat(user.requireRole("ADMIN")).isFalse();
        assertThat(user.requireRole("role_admin")).isFalse();
        assertThat(service.requireRole("ROLE_M2M")).isTrue();
        assertThat(system.requireRole("ROLE_ADMIN")).isFalse();
        assertThat(CloudEventAuthContext.empty().requireRole("ROLE_ADMIN")).isFalse();
        assertThat(CloudEventAuthContextExtractor.from(event("user", "u", null)).requireRole("ROLE_ADMIN")).isFalse();
    }

    @Test
    void knowsExactlyTheThreeCyodaGoAuthTypes() {
        assertThat(CloudEventAuthContext.AUTH_TYPES).containsExactlyInAnyOrder("user", "service", "system");
    }

    @Test
    void authTypeParsingDoesNotDependOnTheDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")); // "service".toUpperCase() is "SERVİCE" here
        try {
            CloudEventAuthContext ctx = CloudEventAuthContextExtractor.from(event("service", "c", "ROLE_M2M"));

            assertThat(ctx.type()).isEqualTo(CloudEventAuthContext.Type.SERVICE);
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
