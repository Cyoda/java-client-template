package com.java_template.common.grpc.client.event_handling;

import io.cloudevents.v1.proto.CloudEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log summary of an inbound CloudEvent carries only its type, id, source and the request/entity ids of its
 * payload: never the callout's tx-token (a bearer credential for the transaction) nor the caller's identity.
 */
class CloudEventsTest {

    private static final String TX_TOKEN = "eyJhbGciOiJIUzI1NiJ9.secret-tx-token-value.sig";
    private static final String AUTH_ID = "user-7f3c-secret-identity";
    private static final String AUTH_CLAIMS = "ROLE_SECRET_ADMIN,ROLE_M2M";
    private static final String ENTITY_ID = "0f8e7d6c-5b4a-3928-1706-f5e4d3c2b1a0";

    private static CloudEvent.CloudEventAttributeValue str(String v) {
        return CloudEvent.CloudEventAttributeValue.newBuilder().setCeString(v).build();
    }

    private static CloudEvent callout() {
        return CloudEvent.newBuilder()
                .setId("ce-1")
                .setSource("urn:cyoda")
                .setSpecVersion("1.0")
                .setType("EntityProcessorCalculationRequest")
                .putAttributes("cyodatxtoken", str(TX_TOKEN))
                .putAttributes("authtype", str("user"))
                .putAttributes("authid", str(AUTH_ID))
                .putAttributes("authclaims", str(AUTH_CLAIMS))
                .setTextData("{\"requestId\":\"req-42\",\"entityId\":\"" + ENTITY_ID + "\",\"payload\":{\"data\":{}}}")
                .build();
    }

    @Test
    void theSummaryNamesTypeIdSourceAndThePayloadIds() {
        String summary = CloudEvents.describe(callout());

        assertThat(summary)
                .contains("EntityProcessorCalculationRequest")
                .contains("ce-1")
                .contains("urn:cyoda")
                .contains("req-42")
                .contains(ENTITY_ID);
    }

    @Test
    void theSummaryNeverCarriesTheTxTokenOrTheCallersIdentity() {
        String summary = CloudEvents.describe(callout());

        assertThat(summary)
                .doesNotContain(TX_TOKEN)
                .doesNotContain("secret-tx-token-value")
                .doesNotContain(AUTH_ID)
                .doesNotContain(AUTH_CLAIMS)
                .doesNotContain("ROLE_SECRET_ADMIN")
                .doesNotContain("cyodatxtoken")
                .doesNotContain("authid")
                .doesNotContain("authclaims");
    }

    @Test
    void theWholeEventsOwnToStringWouldHaveLeakedThem() {
        // guards the premise: logging the CloudEvent object itself prints every attribute
        assertThat(callout().toString()).contains(TX_TOKEN).contains(AUTH_ID);
    }

    @Test
    void aNullOrEmptyEventIsDescribedWithoutFailing() {
        assertThat(CloudEvents.describe(null)).isEqualTo("CloudEvent[null]");
        assertThat(CloudEvents.describe(CloudEvent.getDefaultInstance())).startsWith("CloudEvent[");
    }
}
