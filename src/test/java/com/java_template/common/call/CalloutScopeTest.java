package com.java_template.common.call;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    @Test
    void differentForwardedTokensHaveDifferentFingerprints() {
        String fingerprintA = CyodaCallContext.forward("token-a").fingerprint();
        String fingerprintB = CyodaCallContext.forward("token-b").fingerprint();

        assertThat(fingerprintA).isNotEqualTo(fingerprintB);
    }

    @Test
    void openClearsAndCloseRestoresTheSecurityContext() {
        Authentication original = new UsernamePasswordAuthenticationToken("alice", "pw",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(original);
        try {
            try (CalloutScope ignored = CalloutScope.open("sc-restore")) {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            }
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isEqualTo(original);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void closeCalledTwiceDoesNotThrow() {
        CalloutScope scope = CalloutScope.open("double-close");
        scope.close();

        assertThatCode(scope::close).doesNotThrowAnyException();
        assertThat(CalloutScope.current()).isEmpty();
    }

    @Test
    void wrapRunnableCarriesTheScopeToAnotherThread() throws Exception {
        AtomicReference<Optional<CalloutScope>> seen = new AtomicReference<>();
        try (CalloutScope ignored = CalloutScope.open("wrap-runnable")) {
            Thread thread = new Thread(CalloutScope.wrap(() -> seen.set(CalloutScope.current())));
            thread.start();
            thread.join();
        }
        assertThat(seen.get()).map(CalloutScope::txToken).contains("wrap-runnable");
    }

    @Test
    void wrapCallableCarriesTheScopeToAnotherThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String seenToken;
            try (CalloutScope ignored = CalloutScope.open("wrap-callable")) {
                Callable<String> task = CalloutScope.wrap(
                        () -> CalloutScope.current().map(CalloutScope::txToken).orElse(null));
                seenToken = executor.submit(task).get();
            }
            assertThat(seenToken).isEqualTo("wrap-callable");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aSingleThreadExecutorForgetsTheScopeAfterAWrappedTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (CalloutScope ignored = CalloutScope.open("reused-thread")) {
                String wrappedSeen = executor.submit(CalloutScope.wrap(
                        () -> CalloutScope.current().map(CalloutScope::txToken).orElse(null))).get();
                assertThat(wrappedSeen).isEqualTo("reused-thread");
            }

            Optional<CalloutScope> unwrappedSeen = executor.submit(CalloutScope::current).get();
            assertThat(unwrappedSeen).isEmpty();
        } finally {
            executor.shutdown();
        }
    }
}
