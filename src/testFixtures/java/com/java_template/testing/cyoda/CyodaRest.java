package com.java_template.testing.cyoda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal REST client for tier-1 assertions against cyoda-go (mock IAM: no Authorization header). */
public final class CyodaRest {

    public record Response(int status, JsonNode body, String raw) {
        public Response requireSuccess() {
            if (status < 200 || status >= 300) {
                throw new AssertionError("cyoda answered " + status + ": " + raw);
            }
            return this;
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String apiUrl;

    public CyodaRest(String apiUrl) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    }

    public String apiUrl() {
        return apiUrl;
    }

    public Response get(String path) {
        return send("GET", path, null);
    }

    public Response post(String path, Object body) {
        return send("POST", path, body);
    }

    public Response put(String path, Object body) {
        return send("PUT", path, body);
    }

    public Response delete(String path) {
        return send("DELETE", path, null);
    }

    private Response send(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + "/" + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, publisher)
                    .build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = r.body() == null || r.body().isBlank() ? null : OM.readTree(r.body());
            } catch (IOException notJson) {
                // keep raw only
            }
            return new Response(r.statusCode(), json, r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
