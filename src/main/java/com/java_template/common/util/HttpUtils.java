package com.java_template.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.call.CyodaCallContext;
import com.java_template.common.config.Config;
import com.java_template.common.config.CyodaObjectMapper;
import com.java_template.common.exception.CyodaCredentialException;
import com.java_template.common.exception.CyodaErrors;
import com.java_template.common.util.http.ContentTypeAwareParser;
import com.java_template.common.util.http.ResponseBodyParser;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


/**
 * ABOUTME: Utility component providing HTTP client operations for REST API communication
 * with configurable response parsing strategies for different content types.
 * <p>
 * Every request carries the caller's {@link CyodaCallContext} (spec §4.2): the Authorization header
 * comes from the context's credential, and X-Tx-Token is attached only when the context is joined
 * and the path is tx-routed ({@link #isTxRouted(String)}). A 401 against an M2M credential invalidates
 * the cached token and retries once, but only outside a joined call; inside a joined call the failure
 * is mapped by {@link CyodaErrors#fromHttp} to {@code CyodaCalloutEndedException} instead.
 * <p>
 * A request carrying a credential (M2M or forwarded) goes only to the origin of {@code app.config.cyoda-api-url};
 * any other origin is refused with {@link IllegalArgumentException} before any network I/O.
 */
@Component
public class HttpUtils {
    private final HttpClient client;
    private final Logger logger = LoggerFactory.getLogger(HttpUtils.class);
    private final ObjectMapper om;
    private final JsonUtils jsonUtils;
    private final CyodaTokenSource tokenSource;
    private final ResponseBodyParser defaultParser;
    /** Origin of app.config.cyoda-api-url: the only one a Cyoda credential is ever sent to. */
    private final URI cyodaOrigin;

    public HttpUtils(JsonUtils jsonUtils, CyodaObjectMapper wireMapper, Config config, CyodaTokenSource tokenSource) {
        this.jsonUtils = jsonUtils;
        this.om = wireMapper.mapper();
        this.tokenSource = tokenSource;
        this.defaultParser = ContentTypeAwareParser.createDefault(om);
        this.client = SslUtils.createHttpClient(config);
        this.cyodaOrigin = config.getCyodaApiUrl() == null ? null : URI.create(config.getCyodaApiUrl());
    }

    /** True for a path routed to the transaction owner: entity, search or message (spec §4.4). */
    public static boolean isTxRouted(String path) {
        String p = path == null ? "" : (path.startsWith("/") ? path.substring(1) : path);
        return p.startsWith("entity") || p.startsWith("search") || p.startsWith("message");
    }

    /** A request ready to send, with the M2M token it carries (null for another credential). */
    private record Prepared(HttpRequest request, String m2mToken) {
    }

    private Prepared createRequest(CyodaCallContext ctx, String url, String path, String method, Object data) {
        URI target = URI.create(url);
        requireCyodaOriginForCredential(ctx, target);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(target)
                .header("Content-Type", "application/json");
        String m2mToken = null;
        switch (ctx.credential()) {
            case CyodaCallContext.None ignored -> { }
            case CyodaCallContext.M2m ignored -> {
                m2mToken = tokenSource.bearerToken()
                        .orElseThrow(() -> new CyodaCredentialException("no M2M token source is configured"));
                builder.header("Authorization", "Bearer " + m2mToken);
            }
            case CyodaCallContext.Forward forward -> builder.header("Authorization", "Bearer " + forward.token());
        }
        if (ctx.isJoined() && isTxRouted(path)) {
            builder.header("X-Tx-Token", ctx.txToken());
        }
        HttpRequest.BodyPublisher body = data == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonUtils.toJson(data), StandardCharsets.UTF_8);
        return new Prepared(builder.method(method, body).build(), m2mToken);
    }

    private CompletableFuture<HttpResponse<String>> send(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<ObjectNode> sendRequest(CyodaCallContext ctx, String url, String path, String method,
                                                       Object data, ResponseBodyParser parser) {
        Prepared first = createRequest(ctx, url, path, method, data);
        return send(first.request()).thenCompose(response -> {
            if (response.statusCode() == 401 && first.m2mToken() != null) {
                // only the token cyoda refused: a token another thread fetched since is kept
                tokenSource.invalidate(first.m2mToken());
                if (!ctx.isJoined()) {
                    return send(createRequest(ctx, url, path, method, data).request());
                }
            }
            return CompletableFuture.completedFuture(response);
        }).thenApply(response -> {
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode >= 200 && statusCode < 300) {
                logger.debug("[{}] {} {} succeeded", statusCode, method, url);
            } else if (statusCode >= 300 && statusCode < 400) {
                logger.info("[{}] {} {} redirect: {}", statusCode, method, url, responseBody);
            } else if (statusCode >= 400) {
                throw CyodaErrors.fromHttp(statusCode, responseBody, ctx.isJoined());
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse(null);

            return parser.parse(responseBody, contentType, statusCode);
        });
    }

    private CompletableFuture<ObjectNode> sendRequest(CyodaCallContext ctx, String url, String path, String method, Object data) {
        return sendRequest(ctx, url, path, method, data, defaultParser);
    }

    // Public API methods with default parser

    public CompletableFuture<ObjectNode> sendGetRequest(CyodaCallContext ctx, String apiUrl, String path, Map<String, String> params) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(ctx, fullUrl, path, "GET", null);
    }

    public CompletableFuture<ObjectNode> sendGetRequest(CyodaCallContext ctx, String apiUrl, String path) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "GET", null);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(CyodaCallContext ctx, String apiUrl, String path, Object data) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "POST", data);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(CyodaCallContext ctx, String apiUrl, String path, Object data, Map<String, String> params) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(ctx, fullUrl, path, "POST", data);
    }

    public CompletableFuture<ObjectNode> sendPutRequest(CyodaCallContext ctx, String apiUrl, String path, Object data) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "PUT", data);
    }

    public CompletableFuture<ObjectNode> sendDeleteRequest(CyodaCallContext ctx, String apiUrl, String path) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "DELETE", null);
    }

    // Public API methods with custom parser

    public CompletableFuture<ObjectNode> sendGetRequest(CyodaCallContext ctx, String apiUrl, String path,
                                                         Map<String, String> params, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(ctx, fullUrl, path, "GET", null, parser);
    }

    public CompletableFuture<ObjectNode> sendGetRequest(CyodaCallContext ctx, String apiUrl, String path,
                                                         ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "GET", null, parser);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(CyodaCallContext ctx, String apiUrl, String path,
                                                          Object data, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "POST", data, parser);
    }

    public CompletableFuture<ObjectNode> sendPostRequest(CyodaCallContext ctx, String apiUrl, String path, Object data,
                                                          Map<String, String> params, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, params);
        return sendRequest(ctx, fullUrl, path, "POST", data, parser);
    }

    public CompletableFuture<ObjectNode> sendPutRequest(CyodaCallContext ctx, String apiUrl, String path,
                                                         Object data, ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "PUT", data, parser);
    }

    public CompletableFuture<ObjectNode> sendDeleteRequest(CyodaCallContext ctx, String apiUrl, String path,
                                                            ResponseBodyParser parser) {
        String fullUrl = buildUrlWithParams(apiUrl, path, null);
        return sendRequest(ctx, fullUrl, path, "DELETE", null, parser);
    }

    /**
     * Refuses a request that would carry a Cyoda credential (M2M or a forwarded user token) to any origin but
     * the configured {@code cyoda-api-url}: those tokens are Cyoda's, and must never reach another host. Checked
     * before the token is resolved and before any network I/O. A request with no credential may go anywhere.
     */
    private void requireCyodaOriginForCredential(CyodaCallContext ctx, URI target) {
        if (ctx.credential() instanceof CyodaCallContext.None) {
            return;
        }
        if (cyodaOrigin == null || !sameOrigin(cyodaOrigin, target)) {
            throw new IllegalArgumentException("refusing to send a Cyoda credential to " + describeOrigin(target)
                    + ": credentials go only to the configured app.config.cyoda-api-url origin ("
                    + (cyodaOrigin == null ? "not set" : describeOrigin(cyodaOrigin)) + ")");
        }
    }

    /** Same scheme, host and port (default ports made explicit; scheme and host compared case-insensitively). */
    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private static String describeOrigin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
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

}
