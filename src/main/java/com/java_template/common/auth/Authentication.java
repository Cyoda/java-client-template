package com.java_template.common.auth;

import com.java_template.common.config.Config;
import com.java_template.common.exception.CyodaCredentialException;
import com.java_template.common.util.SslUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;


/**
 * ABOUTME: OAuth2 authentication component providing client credentials flow
 * for secure communication with Cyoda platform services.
 */
@Service
@ConditionalOnAuthMode(Config.AuthMode.CLIENT_CREDENTIALS)
public class Authentication implements CyodaTokenSource {

    private static final Logger logger = LoggerFactory.getLogger(Authentication.class);
    private static final String REGISTRATION_ID = "cyoda";
    private static final String PRINCIPAL_NAME = "cyoda-client";

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    // The manager stores the authorized client here and hands it back while its token is unexpired, so
    // invalidation must remove it here too, or the "refetch" would return the token Cyoda just rejected.
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final Config config;

    // Guards the token HTTP fetch. A ReentrantLock (not a `synchronized` block or ConcurrentHashMap.compute
    // bin lock) so the blocking OAuth2 token request never pins a virtual thread (spec §4.5, JDK 21).
    private final ReentrantLock fetchLock = new ReentrantLock();
    private volatile CachedToken cached;

    public Authentication(Config config) {
        this.config = config;

        if (isBlank(config.getCyodaClientId()) || isBlank(config.getCyodaClientSecret())) {
            throw new IllegalStateException("app.config.cyoda-client-id and app.config.cyoda-client-secret must be set "
                    + "when app.config.auth-mode=client-credentials (use auth-mode=none for cyoda-go mock IAM)");
        }

        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .tokenUri(config.getCyodaApiUrl() + "/oauth/token")
                .clientId(config.getCyodaClientId())
                .clientSecret(config.getCyodaClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();

        var registrationRepo = new InMemoryClientRegistrationRepository(registration);
        var clientService = new InMemoryOAuth2AuthorizedClientService(registrationRepo);
        this.authorizedClientService = clientService;
        AuthorizedClientServiceOAuth2AuthorizedClientManager acm = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                registrationRepo, clientService
        );

        // We have to inject a different OAuth2AuthorizedClientProvider which is built using RestClientClientCredentialsTokenResponseClient
        // rather than the deprecated DefaultClientCredentialsTokenResponseClient.
        // This fixes the issue we were seeing with error responses where the response body was getting lost.
        // We also inject a custom RestTemplate which uses our custom HttpClient to handle SSL trust.
        RestClientClientCredentialsTokenResponseClient accessTokenResponseClient = new RestClientClientCredentialsTokenResponseClient();

        // Connect timeout from SslUtils; a read timeout of grpc-call-deadline-ms, so a hung token endpoint cannot
        // hold fetchLock (and every caller waiting on it) indefinitely.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(SslUtils.createHttpClient(config));
        requestFactory.setReadTimeout(Duration.ofMillis(config.getGrpcCallDeadlineMs()));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters((messageConverters) -> {
                    messageConverters.clear();
                    messageConverters.add(new FormHttpMessageConverter());
                    messageConverters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();

        accessTokenResponseClient.setRestClient(restClient);
        OAuth2AuthorizedClientProvider acp = OAuth2AuthorizedClientProviderBuilder
                .builder()
                .clientCredentials(builder -> builder.accessTokenResponseClient(accessTokenResponseClient))
                .build();

        acm.setAuthorizedClientProvider(acp);

        this.authorizedClientManager = acm;
    }

    /**
     * Returns a valid access token, reusing it if still fresh.
     */
    public OAuth2AccessToken getAccessToken() {
        CachedToken current = cached;
        if (current != null && current.isValid()) {
            return current.oAuth2AccessToken;
        }

        lockInterruptibly();
        try {
            current = cached;
            if (current != null && current.isValid()) {
                return current.oAuth2AccessToken;
            }

            logger.info("Fetching new OAuth2 access token");
            OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                    .principal(PRINCIPAL_NAME)
                    .build();

            OAuth2AuthorizedClient client = authorizedClientManager.authorize(request);
            if (client == null || client.getAccessToken() == null) {
                throw new IllegalStateException("Failed to obtain access token");
            }

            OAuth2AccessToken accessToken = client.getAccessToken();
            logger.info("New token fetched, expires at: {}", accessToken.getExpiresAt());
            cached = new CachedToken(accessToken);
            return accessToken;
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * Clears the cached token, whatever it is, so the next call re-authenticates. Prefer
     * {@link #invalidate(String)} with the token Cyoda rejected: it never discards a token another thread
     * fetched in the meantime.
     */
    public void invalidateTokens() {
        invalidate(null);
    }

    /**
     * Waits for {@code fetchLock} (held by another thread's fetch) interruptibly: an interrupted caller gives up
     * with {@link CyodaCredentialException}, its interrupt flag restored.
     */
    private void lockInterruptibly() {
        try {
            fetchLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CyodaCredentialException("interrupted while waiting for the M2M access token");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public Optional<String> bearerToken() {
        return Optional.of(getAccessToken().getTokenValue());
    }

    @Override
    public void invalidate() {
        invalidate(null);
    }

    /**
     * Drops {@code rejectedToken} from both this cache and the authorized-client service, so the next
     * {@link #getAccessToken()} really fetches a new token. Compared under {@code fetchLock}: if either
     * holds a different token (fetched by another thread since the rejected one was sent), that one is
     * kept. {@code null} drops whatever is held.
     */
    @Override
    public void invalidate(String rejectedToken) {
        lockInterruptibly();
        try {
            CachedToken current = cached;
            if (current != null && (rejectedToken == null || rejectedToken.equals(current.getTokenValue()))) {
                cached = null;
            }
            OAuth2AuthorizedClient stored = authorizedClientService.loadAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME);
            if (stored != null && (rejectedToken == null
                    || (stored.getAccessToken() != null && rejectedToken.equals(stored.getAccessToken().getTokenValue())))) {
                authorizedClientService.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME);
                logger.info("Invalidated the cached M2M access token");
            }
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * Simple container for access token with expiry.
     */
    private record CachedToken(OAuth2AccessToken oAuth2AccessToken) {

        public boolean isValid() {
            Instant expiresAt = this.oAuth2AccessToken.getExpiresAt();
            return expiresAt != null && Instant.now().isBefore(expiresAt.minusSeconds(60));
        }

        public String getTokenValue() {
            return oAuth2AccessToken.getTokenValue();
        }
    }
}