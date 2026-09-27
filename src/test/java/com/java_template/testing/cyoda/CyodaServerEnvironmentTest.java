package com.java_template.testing.cyoda;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaServerEnvironmentTest {

    @Test
    void theEnvironmentIsBuiltFromScratchAndIsolatedFromUserConfig() {
        Path home = Path.of("/tmp/cyoda-home-x");

        Map<String, String> env = CyodaServer.environment(Profile.mockMemory(), 18080, 19090, 19091, home, "/usr/bin:/bin");

        assertThat(env).containsEntry("PATH", "/usr/bin:/bin")
                .containsEntry("HOME", home.toString())
                .containsEntry("XDG_CONFIG_HOME", home.resolve(".config").toString())
                .containsEntry("CYODA_HTTP_PORT", "18080")
                .containsEntry("CYODA_GRPC_PORT", "19090")
                .containsEntry("CYODA_ADMIN_PORT", "19091")
                .containsEntry("CYODA_CONTEXT_PATH", "/api")
                .containsEntry("CYODA_STORAGE_BACKEND", "memory")
                .containsEntry("CYODA_IAM_MODE", "mock")
                .containsEntry("CYODA_IAM_MOCK_KIND", "user")
                .containsEntry("CYODA_IAM_MOCK_ROLES", "ROLE_ADMIN,ROLE_M2M")
                .containsEntry("CYODA_KEEPALIVE_INTERVAL", "10")
                .containsEntry("CYODA_KEEPALIVE_TIMEOUT", "30")
                .containsEntry("CYODA_DISPATCH_WAIT_TIMEOUT", "5s")
                .containsEntry("CYODA_SCHEDULER_SCAN_INTERVAL", "50ms")
                .containsEntry("CYODA_SUPPRESS_BANNER", "true")
                .containsEntry("CYODA_ERROR_RESPONSE_MODE", "verbose")
                .doesNotContainKey("CYODA_PROFILES");
    }

    @Test
    void profileOverridesWin() {
        Map<String, String> env = CyodaServer.environment(CyodaProfiles.byName(CyodaProfiles.KEEPALIVE_SHORT),
                1, 2, 3, Path.of("/tmp/h"), "/bin");

        assertThat(env).containsEntry("CYODA_KEEPALIVE_INTERVAL", "1").containsEntry("CYODA_KEEPALIVE_TIMEOUT", "3");
    }
}
