package com.java_template.common.call;

import com.java_template.common.auth.CyodaTokenSource;
import com.java_template.common.exception.CyodaRetryableException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CyodaGrpcCallsTest {

    private final CyodaTokenSource tokens = mock(CyodaTokenSource.class);

    @Test
    void m2mRetriesOnceAfterUnauthenticated() {
        AtomicInteger calls = new AtomicInteger();

        String r = CyodaGrpcCalls.call(CyodaCallContext.m2m(), tokens, () -> {
            if (calls.incrementAndGet() == 1) {
                throw Status.UNAUTHENTICATED.asRuntimeException();
            }
            return "ok";
        });

        assertThat(r).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        verify(tokens).invalidate();
    }

    @Test
    void aForwardedTokenIsNeverRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> CyodaGrpcCalls.call(CyodaCallContext.forward("u"), tokens, () -> {
            calls.incrementAndGet();
            throw Status.UNAUTHENTICATED.asRuntimeException();
        }));
        assertThat(calls).hasValue(1);
        verifyNoInteractions(tokens);
    }

    @Test
    void joinedQueueFullIsRetriedAtMostThreeTimes() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> CyodaGrpcCalls.call(CyodaCallContext.m2m().withTxToken("t"), tokens, () -> {
            calls.incrementAndGet();
            throw new CyodaRetryableException("TOO_MANY_JOINED_REQUESTS", "full");
        })).isInstanceOf(CyodaRetryableException.class);
        assertThat(calls).hasValue(4);
    }

    @Test
    void aRetryableConflictIsNotRetriedLocally() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> CyodaGrpcCalls.call(CyodaCallContext.m2m(), tokens, () -> {
            calls.incrementAndGet();
            throw new CyodaRetryableException("CONFLICT", "c");
        }));
        assertThat(calls).hasValue(1);
    }
}
