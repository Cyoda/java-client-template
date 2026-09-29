package com.java_template.common.call;

import com.java_template.common.config.Config;
import com.java_template.common.exception.CyodaCalloutEndedException;
import com.java_template.common.exception.CyodaCredentialException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyodaCallContextsTest {

    private final Config clientCredentials = new Config();
    private final CyodaCallContexts contexts = new CyodaCallContexts(clientCredentials);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        CalloutScope.current().ifPresent(CalloutScope::close);
    }

    private static JwtAuthenticationToken jwt(String value) {
        Jwt token = Jwt.withTokenValue(value).header("alg", "RS256").subject("u1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token);
    }

    /**
     * {@code Jwt}'s own constructor rejects a blank token value ({@code AbstractOAuth2Token}'s
     * {@code Assert.hasText}), so a real decoded JWT can never carry one. This exercises
     * {@code CyodaCallContexts}' defensive check anyway, via a Jwt built with a valid value whose
     * {@code getTokenValue()} is then overridden to return blank.
     */
    private static JwtAuthenticationToken jwtWithBlankTokenValue() {
        Jwt base = Jwt.withTokenValue("placeholder").header("alg", "RS256").subject("u1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        Jwt blank = new Jwt(base.getTokenValue(), base.getIssuedAt(), base.getExpiresAt(), base.getHeaders(), base.getClaims()) {
            @Override
            public String getTokenValue() {
                return " ";
            }
        };
        return new JwtAuthenticationToken(blank);
    }

    @Test
    void rule1_authModeNoneSendsNothingButKeepsTheScopeToken() {
        Config none = new Config();
        none.setAuthMode(Config.AuthMode.NONE);
        CyodaCallContexts noneContexts = new CyodaCallContexts(none);

        assertThat(noneContexts.current()).isEqualTo(CyodaCallContext.none());
        try (CalloutScope ignored = CalloutScope.open("tx-1")) {
            assertThat(noneContexts.current()).isEqualTo(CyodaCallContext.none().withTxToken("tx-1"));
        }
    }

    @Test
    void rule2_anOpenScopeMeansM2mPlusTokenWhateverTheSecurityContextHolds() {
        SecurityContextHolder.getContext().setAuthentication(jwt("user-token"));
        try (CalloutScope ignored = CalloutScope.open("tx-2")) {
            assertThat(contexts.current()).isEqualTo(CyodaCallContext.m2m().withTxToken("tx-2"));
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void rule3_aUserJwtIsForwardedAndABlankOneIsAnError() {
        SecurityContextHolder.getContext().setAuthentication(jwt("user-token"));
        assertThat(contexts.current()).isEqualTo(CyodaCallContext.forward("user-token"));

        SecurityContextHolder.getContext().setAuthentication(jwtWithBlankTokenValue());
        assertThatThrownBy(contexts::current).isInstanceOf(CyodaCredentialException.class);
    }

    @Test
    void rule4_anotherAuthenticatedPrincipalIsAnErrorNotADowngrade() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "pw", AuthorityUtils.createAuthorityList("ROLE_USER")));

        assertThatThrownBy(contexts::current)
                .isInstanceOf(CyodaCredentialException.class)
                .hasMessageContaining("UsernamePasswordAuthenticationToken");
    }

    @Test
    void rule5_noPrincipalOrAnonymousIsM2m() {
        assertThat(contexts.current()).isEqualTo(CyodaCallContext.m2m());
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(contexts.current()).isEqualTo(CyodaCallContext.m2m());
    }

    @Test
    void memberStreamIsM2mOrNoneAndNeverJoined() {
        try (CalloutScope ignored = CalloutScope.open("tx-3")) {
            assertThat(contexts.forMemberStream()).isEqualTo(CyodaCallContext.m2m());
        }
        Config none = new Config();
        none.setAuthMode(Config.AuthMode.NONE);
        assertThat(new CyodaCallContexts(none).forMemberStream()).isEqualTo(CyodaCallContext.none());
    }

    @Test
    void aClosedScopeFailsLocally() {
        CalloutScope scope = CalloutScope.open("tx-4");
        scope.end();

        assertThatThrownBy(contexts::current).isInstanceOf(CyodaCalloutEndedException.class);
        scope.close();
    }

    @Test
    void aThreadTheAppStartedWithoutWrapGoesOutUnjoinedAsM2m() throws Exception {
        try (CalloutScope ignored = CalloutScope.open("tx-5")) {
            CyodaCallContext fromPlainThread = CompletableFuture.supplyAsync(contexts::current, r -> new Thread(r).start()).get();
            CyodaCallContext fromWrapped = CompletableFuture.supplyAsync(CalloutScope.wrapSupplier(contexts::current),
                    r -> new Thread(r).start()).get();

            assertThat(fromPlainThread).isEqualTo(CyodaCallContext.m2m());
            assertThat(fromWrapped).isEqualTo(CyodaCallContext.m2m().withTxToken("tx-5"));
        }
    }

    @Test
    void unjoinedRunsAsM2mWithoutTheToken() {
        try (CalloutScope ignored = CalloutScope.open("tx-6")) {
            assertThat(CalloutScope.unjoined(contexts::current)).isEqualTo(CyodaCallContext.m2m());
            assertThat(contexts.current().txToken()).isEqualTo("tx-6");
        }
    }

    @Test
    void unjoinedOutsideACalloutKeepsTheCallersCredential() {
        // spec §4.2: the framework never silently downgrades to M2M. unjoined() only detaches a
        // callout's transaction; outside a callout there is nothing to detach, so the caller's own
        // credential (here, a forwarded user JWT) must come through unchanged.
        SecurityContextHolder.getContext().setAuthentication(jwt("user-token"));

        assertThat(CalloutScope.unjoined(contexts::current)).isEqualTo(CyodaCallContext.forward("user-token"));
    }

    @Test
    void aWrappedTaskRunningAfterEndSeesTheCalloutAsEnded() throws Exception {
        CalloutScope scope = CalloutScope.open("tx-7");
        Callable<CyodaCallContext> wrapped = CalloutScope.wrap((Callable<CyodaCallContext>) contexts::current);
        scope.end();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> executor.submit(wrapped).get())
                    .hasCauseInstanceOf(CyodaCalloutEndedException.class);
        } finally {
            executor.shutdown();
            scope.close();
        }
    }
}
