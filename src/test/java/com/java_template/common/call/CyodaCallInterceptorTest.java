package com.java_template.common.call;

import com.java_template.common.auth.CyodaTokenSource;
import io.grpc.*;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CyodaCallInterceptorTest {

    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TX = Metadata.Key.of("tx-token", Metadata.ASCII_STRING_MARSHALLER);

    private final CyodaTokenSource tokens = mock(CyodaTokenSource.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Metadata start(MethodDescriptor<?, ?> method, CallOptions options, ClientCall.Listener<Object> listener) {
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);
        Metadata headers = new Metadata();
        new CyodaCallInterceptor(tokens).interceptCall((MethodDescriptor) method, options, channel).start(listener, headers);
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void m2mJoinedEntityCallCarriesBearerAndTxToken() {
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CallOptions opts = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.m2m().withTxToken("tx"));

        Metadata h = start(CloudEventsServiceGrpc.getEntityManageMethod(), opts, mock(ClientCall.Listener.class));

        assertThat(h.get(AUTH)).isEqualTo("Bearer m2m");
        assertThat(h.get(TX)).isEqualTo("tx");
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelAdminNeverCarriesTheTxToken() {
        when(tokens.bearerToken()).thenReturn(Optional.of("m2m"));
        CallOptions opts = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.m2m().withTxToken("tx"));

        Metadata h = start(CloudEventsServiceGrpc.getEntityModelManageMethod(), opts, mock(ClientCall.Listener.class));

        assertThat(h.get(TX)).isNull();
        assertThat(h.get(AUTH)).isEqualTo("Bearer m2m");
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardSendsTheUserTokenAndNoneSendsNothing() {
        CallOptions fwd = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.forward("user"));
        CallOptions none = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.none());

        assertThat(start(CloudEventsServiceGrpc.getEntitySearchMethod(), fwd, mock(ClientCall.Listener.class)).get(AUTH))
                .isEqualTo("Bearer user");
        assertThat(start(CloudEventsServiceGrpc.getEntitySearchMethod(), none, mock(ClientCall.Listener.class)).get(AUTH))
                .isNull();
        verifyNoInteractions(tokens);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallWithoutContextIsCancelled() {
        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);

        start(CloudEventsServiceGrpc.getEntityManageMethod(), CallOptions.DEFAULT, listener);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(listener).onClose(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCredentialFailureCancelsWithUnauthenticated() {
        when(tokens.bearerToken()).thenThrow(new IllegalStateException("down"));
        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);
        CallOptions opts = CallOptions.DEFAULT.withOption(CyodaCallInterceptor.CONTEXT, CyodaCallContext.m2m());

        start(CloudEventsServiceGrpc.getEntityManageMethod(), opts, listener);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(listener).onClose(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }
}
