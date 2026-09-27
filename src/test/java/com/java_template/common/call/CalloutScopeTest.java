package com.java_template.common.call;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalloutScopeTest {

    @Test
    void closeRestoresThePreviousScopeAndClearsTheThread() {
        try (CalloutScope outer = CalloutScope.open("outer")) {
            try (CalloutScope inner = CalloutScope.open("inner")) {
                assertThat(CalloutScope.current()).contains(inner);
            }
            assertThat(CalloutScope.current()).contains(outer);
        }
        assertThat(CalloutScope.current()).isEmpty();
    }

    @Test
    void fingerprintsNeverContainTheTokens() {
        CyodaCallContext ctx = CyodaCallContext.forward("secret-user-token").withTxToken("secret-tx");

        assertThat(ctx.fingerprint()).doesNotContain("secret").startsWith("fwd:");
        assertThat(ctx.toString()).doesNotContain("secret");
    }
}
