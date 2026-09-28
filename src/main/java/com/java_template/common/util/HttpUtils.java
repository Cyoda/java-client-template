package com.java_template.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.util.http.ContentTypeAwareParser;
import com.java_template.common.util.http.ResponseBodyParser;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


/**
 * ABOUTME: Utility component providing HTTP client operations for REST API communication
 * with configurable response parsing strategies for different content types.
 */
@Component
public class HttpUtils {
    private final HttpClient client;
    private final Logger logger = LoggerFactory.getLogger(HttpUtils.class);
    private final ObjectMapper om;
    private final JsonUtils jsonUtils;
    private final ResponseBodyParser defaultParser;

    public HttpUtils(JsonUtils jsonUtils, CyodaObjectMapper wireMapper, Config config) {
        this.jsonUtils = jsonUtils;
        this.om = wireMapper.mapper();
        this.defaultParser = ContentTypeAwareParser.createDefault(om);
        this.client = SslUtils.createHttpClient(config);
    }

    private String ensureBearerToken(String token) {
        return token.startsWith("Bearer") ? token : "Bearer " + token;
    }

    private HttpRequest.Builder createRequestBuilder(String url, String token, String method) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", ensureBearerToken(token));
        }
        return builder.method(method, HttpRequest.BodyPublishers.noBody());
    }


    private HttpRequest createRequest(String url, String token, String method, Object data) {
        HttpRequest.Builder builder = createRequestBuilder(url, token, method);
        if (data != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonUtils.toJson(data), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private CompletableFuture<ObjectNode> sendRequest(String url, String token, String method, Object data,
                                                       ResponseBodyParser parser) {
        HttpRequest request = createRequest(url, token, method, data);
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int statusCode = response.statusCode();
                    String responseBody = response.body();

                    if (statusCode >= 200 && statusCode < 300) {
                        logger.debug("[{}] {} {} succeeded", statusCode, method, url);
                    } else if (statusCode >= 300 && statusCode < 400) {
                        logger.info("[{}] {} {} redirect: {}", statusCode, method, url, responseBody);
                    } else if (statusCode >= 400 && statusCode < 500) {
                        throw new ResponseStatusException(HttpStatus.valueOf(statusCode), extractErrorMessage(responseBody));
                    } else if (statusCode >= 500) {
                        throw new ResponseStatusException(HttpStatus.valueOf(statusCode), extractErrorMessage(responseBody));
                    }

                    String contentType = response.headers()
                            .firstValue("Content-Type")
                            .orElse(null);

                    return parser.parse(responseBody, contentType, statusCode);
                });
    }

    private CompletableFuture<ObjectNode> sendRequest(String url, String token, String method, Object data) {
        return sendRequest(url, token, method, data, defaultParser);
    }

    // Public API methods with default parser

    public CompletableFuture<ObjectNode> sendGetRequest(String token, String apiUrl, String path, Map<String, String> params) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(fullUrl, token, "GET", null);
    }

    public CompletableFuture<ObjectNode> sendGetRequest(String token, String apiUrl, String path) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "GET", null);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(String token, String apiUrl, String path, Object data) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "POST", data);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(String token, String apiUrl, String path, Object data, Map<String, String> params) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(fullUrl, token, "POST", data);
    }

    public CompletableFuture<ObjectNode> sendPutRequest(String token, String apiUrl, String path, Object data) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "PUT", data);
    }

    public CompletableFuture<ObjectNode> sendDeleteRequest(String token, String apiUrl, String path) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "DELETE", null);
    }

    // Public API methods with custom parser

    public CompletableFuture<ObjectNode> sendGetRequest(String token, String apiUrl, String path,
                                                         Map<String, String> params, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(fullUrl, token, "GET", null, parser);
    }

    public CompletableFuture<ObjectNode> sendGetRequest(String token, String apiUrl, String path,
                                                         ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "GET", null, parser);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(String token, String apiUrl, String path,
                                                          Object data, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "POST", data, parser);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(String token, String apiUrl, String path, Object data,
                                                          Map<String, String> params, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(fullUrl, token, "POST", data, parser);
    }

    public CompletableFuture<ObjectNode> sendPutRequest(String token, String apiUrl, String path,
                                                         Object data, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "PUT", data, parser);
    }

    public CompletableFuture<ObjectNode> sendDeleteRequest(String token, String apiUrl, String path,
                                                            ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(fullUrl, token, "DELETE", null, parser);
    }

    private String buildUrlWithParams(String apiUrl, String path, Map<String, String> params) {
        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String fullUrl = (path == null || path.isBlank()) ? baseUrl : baseUrl + "/" + path;

        if (params == null || params.isEmpty()) {
            return fullUrl;
        }
        String queryString = params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return fullUrl + "?" + queryString;
    }

    /**
     * The error message of a failed response. cyoda-go's problem+json carries its error code in
     * {@code properties.errorCode}; when present it prefixes the message ({@code "MODEL_HAS_ENTITIES: cannot
     * unlock: 1 entities exist"}), so callers can tell refusals apart by code, not by wording.
     */
    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode errorNode = om.readTree(responseBody);
            String message = null;
            if (errorNode.has("errorMessage")) {
                message = errorNode.get("errorMessage").asText();
            } else if (errorNode.has("message")) {
                message = errorNode.get("message").asText();
            } else if (errorNode.has("detail")) {
                message = errorNode.get("detail").asText();
            }
            JsonNode errorCode = errorNode.path("properties").path("errorCode");
            if (errorCode.isTextual() && !errorCode.asText().isBlank()) {
                return message == null ? errorCode.asText() : errorCode.asText() + ": " + message;
            }
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {}
        return responseBody;
    }
}
