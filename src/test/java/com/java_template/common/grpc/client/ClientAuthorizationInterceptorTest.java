package com.java_template.common.grpc.client;

import com.java_template.common.auth.Authentication;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.cyoda.cloud.api.grpc.CloudEventsServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;

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
        Authentication auth = mock(Authentication.class);
        when(auth.getAccessToken()).thenReturn(new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "tok", Instant.now(), Instant.now().plusSeconds(600)));
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);

        ClientCall<Object, Object> call = new ClientAuthorizationInterceptor(auth).interceptCall(method, CallOptions.DEFAULT, channel);
        Metadata headers = new Metadata();
        call.start(mock(ClientCall.Listener.class), headers);

        assertThat(headers.get(AUTH)).isEqualTo("Bearer tok");
        verify(delegate).start(any(), same(headers));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cancelsTheCallWhenNoTokenCanBeObtained() {
        Authentication auth = mock(Authentication.class);
        when(auth.getAccessToken()).thenThrow(new IllegalStateException("token endpoint down"));
        ClientCall<Object, Object> delegate = mock(ClientCall.class);
        Channel channel = mock(Channel.class);
        when(channel.newCall(any(), any())).thenReturn((ClientCall) delegate);
        ClientCall.Listener<Object> listener = mock(ClientCall.Listener.class);

        new ClientAuthorizationInterceptor(auth).interceptCall(method, CallOptions.DEFAULT, channel)
                .start(listener, new Metadata());

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(listener).onClose(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(delegate, never()).start(any(), any());
    }
}
