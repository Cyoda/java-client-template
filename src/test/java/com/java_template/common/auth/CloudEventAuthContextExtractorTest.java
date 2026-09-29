package com.java_template.common.auth;

import io.cloudevents.v1.proto.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventAuthContextExtractorTest {

    private CloudEventAuthContextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CloudEventAuthContextExtractor();
    }

    @Test
    void extract_returnsEmpty_whenNoAuthTypeAttribute() {
        CloudEvent event = CloudEvent.newBuilder().build();
        assertThat(extractor.extract(event)).isEmpty();
    }

    @Test
    void extract_returnsContext_whenAuthTypePresent() {
        CloudEvent event = cloudEvent("user", "uuid-1", "{\"roles\":[\"USER\"]}");
        Optional<CloudEventAuthContext> result = extractor.extract(event);
        assertThat(result).isPresent();
        assertThat(result.get().authType()).isEqualTo("user");
        assertThat(result.get().authId()).isEqualTo("uuid-1");
        assertThat(result.get().authClaimsJson()).isEqualTo("{\"roles\":[\"USER\"]}");
    }

    @Test
    void extract_returnsContext_withNullAuthId_whenAuthIdAbsent() {
        CloudEvent event = CloudEvent.newBuilder()
                .putAttributes("authtype", strAttr("service"))
                .build();
        Optional<CloudEventAuthContext> result = extractor.extract(event);
        assertThat(result).isPresent();
        assertThat(result.get().authType()).isEqualTo("service");
        assertThat(result.get().authId()).isNull();
        assertThat(result.get().authClaimsJson()).isNull();
    }

    @Test
    void extract_handlesAllAuthTypeValues() {
        for (String authType : new String[]{"user", "service", "system"}) {
            CloudEvent event = CloudEvent.newBuilder()
                    .putAttributes("authtype", strAttr(authType))
                    .build();
            Optional<CloudEventAuthContext> result = extractor.extract(event);
            assertThat(result).isPresent();
            assertThat(result.get().authType()).isEqualTo(authType);
        }
    }

    @Test
    void isUserContext_trueOnlyForUser() {
        assertThat(new CloudEventAuthContext("user", null, null).isUserContext()).isTrue();
        assertThat(new CloudEventAuthContext("service", null, null).isUserContext()).isFalse();
        assertThat(new CloudEventAuthContext("system", null, null).isUserContext()).isFalse();
        assertThat(new CloudEventAuthContext("unauthenticated", null, null).isUserContext()).isFalse();
    }

    @Test
    void knowsExactlyTheThreeCyodaGoAuthTypes() {
        assertThat(CloudEventAuthContext.AUTH_TYPES).containsExactlyInAnyOrder("user", "service", "system");
    }

    @SuppressWarnings("SameParameterValue")
    private CloudEvent cloudEvent(String authType, String authId, String authClaims) {
        return CloudEvent.newBuilder()
                .putAttributes("authtype",   strAttr(authType))
                .putAttributes("authid",     strAttr(authId))
                .putAttributes("authclaims", strAttr(authClaims))
                .build();
    }

    private CloudEvent.CloudEventAttributeValue strAttr(String value) {
        return CloudEvent.CloudEventAttributeValue.newBuilder().setCeString(value).build();
    }
}
