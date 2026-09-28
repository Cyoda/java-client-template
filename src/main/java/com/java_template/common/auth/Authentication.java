package com.java_template.common.auth;

import com.java_template.common.config.Config;
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

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


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
    private final ConcurrentMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final Config config;

    private static final String CACHE_KEY = "cyoda";

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

        RestClient restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        SslUtils.createHttpClient(config)
                ))
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
        CachedToken token = tokenCache.compute(CACHE_KEY, (key, existing) -> {
            if (existing != null && existing.isValid()) {
                return existing;
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
            return new CachedToken(accessToken);
        });

        return token.oAuth2AccessToken;
    }



    /**
     * Clears cached token so next call re-authenticates.
     */
    public void invalidateTokens() {
        tokenCache.remove(CACHE_KEY);
        authorizedClientService.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME);
        logger.info("Manually invalidated cached token");
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
        invalidateTokens();
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