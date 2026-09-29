package com.java_template.it;

import com.java_template.testing.cyoda.CyodaServer;
import com.java_template.testing.cyoda.CyodaTestEnvironment;
import com.java_template.testing.cyoda.Profile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CyodaServerIT {

    @Test
    void thePinnedCyodaStartsInMemoryAndIsHealthy() throws Exception {
        CyodaServer server = CyodaTestEnvironment.server(Profile.mockMemory());

        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.apiUrl() + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        // cyoda-go logs "storage backend selected" with backend=<chosen> and, separately, the full
        // available="[memory postgres sqlite]" list — assert the chosen backend, not a bare "sqlite"
        // substring, since the available-backends list always mentions sqlite regardless of choice.
        assertThat(server.logTail(200)).contains("backend=memory").doesNotContain("backend=sqlite");
    }
}
