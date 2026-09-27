package com.java_template.common.grpc.client;

import com.java_template.common.auth.CyodaTokenSource;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class ClientAuthorizationInterceptorTest {

    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final MethodDescriptor<Object, Object> method = (MethodDescriptor) CloudEventsServiceGrpc.getEntityManageMethod();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void addsTheM2mBearerToken() {
        CyodaTokenSource source = mock(CyodaTokenSource.class);
        when(source.bearerToken()).thenReturn(java.util.Optional.of("tok"));
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);

        ClientCall<Object, Object> call = new ClientAuthorizationInterceptor(source).interceptCall(method, CallOptions.DEFAULT, channel);
        Metadata headers = new Metadata();
        call.start(mock(ClientCall.Listener.class), headers);

        assertThat(headers.get(AUTH)).isEqualTo("Bearer tok");
        verify(delegate).start(any(), same(headers));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cancelsTheCallWhenNoTokenCanBeObtained() {
        CyodaTokenSource source = mock(CyodaTokenSource.class);
        when(source.bearerToken()).thenThrow(new IllegalStateException("token endpoint down"));
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);
        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);

        new ClientAuthorizationInterceptor(source).interceptCall(method, CallOptions.DEFAULT, channel)
                .start(listener, new Metadata());

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(listener).onClose(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(delegate, never()).start(any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendsNoHeaderInAuthModeNone() {
        CyodaTokenSource source = mock(CyodaTokenSource.class);
        when(source.bearerToken()).thenReturn(java.util.Optional.empty());
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);
        Metadata headers = new Metadata();

        new ClientAuthorizationInterceptor(source).interceptCall(method, CallOptions.DEFAULT, channel)
                .start(mock(ClientCall.Listener.class), headers);

        assertThat(headers.get(AUTH)).isNull();
        verify(delegate).start(any(), same(headers));
    }
}
