package com.java_template.common.grpc.client;

import com.java_template.common.auth.CyodaTokenSource;
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

import java.util.Optional;

/**
 * ABOUTME: gRPC client interceptor that sends the M2M bearer token on every Cyoda call.
 * A call fails with UNAUTHENTICATED when no token can be obtained; it never proceeds unauthenticated.
 */
public class ClientAuthorizationInterceptor implements ClientInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ClientAuthorizationInterceptor.class);
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final CyodaTokenSource tokenSource;

    public ClientAuthorizationInterceptor(CyodaTokenSource tokenSource) {
        this.tokenSource = tokenSource;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        final Optional<String> token;
        try {
            token = tokenSource.bearerToken();
        } catch (RuntimeException e) {
            // Never log the token; the exception comes from the token source and carries no token.
            LOG.warn("Cannot obtain M2M token for {}; failing the call with UNAUTHENTICATED: {}",
                    method.getFullMethodName(), e.toString());
            return new FailedClientCall<>(
                    // Generic description: it can reach a REST client; the cause is for server-side logs only.
                    Status.UNAUTHENTICATED.withDescription("failed to obtain a Cyoda token").withCause(e));
        }
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                token.ifPresent(t -> headers.put(AUTHORIZATION, "Bearer " + t));
                super.start(responseListener, headers);
            }
        };
    }

    /**
     * A call that never reaches the transport: start() closes the listener with the given status and every
     * other method is a no-op. gRPC stubs call request()/sendMessage()/halfClose() after start(), so the
     * delegate must not be left unstarted (it would throw IllegalStateException "Not started").
     */
    private static final class FailedClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
        private final Status status;

        FailedClientCall(Status status) {
            this.status = status;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            responseListener.onClose(status, new Metadata());
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void cancel(String message, Throwable cause) {
        }

        @Override
        public void halfClose() {
        }

        @Override
        public void sendMessage(ReqT message) {
        }
    }
}
