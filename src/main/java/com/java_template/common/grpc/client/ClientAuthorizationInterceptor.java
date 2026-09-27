package com.java_template.common.grpc.client;

import com.java_template.common.auth.Authentication;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ABOUTME: gRPC client interceptor that sends the M2M bearer token on every Cyoda call.
 * A call is cancelled with UNAUTHENTICATED when no token can be obtained; it never proceeds unauthenticated.
 */
public class ClientAuthorizationInterceptor implements ClientInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ClientAuthorizationInterceptor.class);
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Authentication authentication;

    public ClientAuthorizationInterceptor(Authentication authentication) {
        this.authentication = authentication;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                final String token;
                try {
                    token = authentication.getAccessToken().getTokenValue();
                } catch (RuntimeException e) {
                    LOG.error("Cannot obtain M2M token for {} — cancelling the call", method.getFullMethodName(), e);
                    responseListener.onClose(
                            Status.UNAUTHENTICATED.withDescription("M2M token unavailable: " + e.getMessage()).withCause(e),
                            new Metadata());
                    return;
                }
                headers.put(AUTHORIZATION, "Bearer " + token);
                super.start(responseListener, headers);
            }
        };
    }
}
