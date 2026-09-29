package com.java_template.common.call;

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

import java.util.Set;

/**
 * ABOUTME: Sets authorization and (for tx-routed RPCs only) tx-token from the call's {@link CyodaCallContext}.
 * A call without a context, or whose credential cannot be obtained, is cancelled before it ever reaches the
 * transport — never sent unauthenticated.
 *
 * <p>The credential is resolved eagerly, in {@link #interceptCall}, rather than lazily inside
 * {@code ClientCall.start()}. This matters: if resolution fails <em>after</em> a real delegate
 * {@link ClientCall} has been created (via {@code next.newCall(...)}), the only safe way to fail is to still
 * call {@code start()} on it (closing the listener) — gRPC's generated stubs unconditionally call
 * {@code request()}/{@code sendMessage()}/{@code halfClose()} right after {@code start()}, and calling those
 * on a delegate that was never started throws {@code IllegalStateException: Not started}. Resolving the
 * credential first means a failure can be reported with a self-contained {@link FailedClientCall} that never
 * touches {@code next.newCall(...)} at all, so there is no unstarted delegate to protect.
 */
public final class CyodaCallInterceptor implements ClientInterceptor {

    public static final CallOptions.Key<CyodaCallContext> CONTEXT = CallOptions.Key.create("cyoda-call-context");
    /** The only RPCs cyoda routes into a joined transaction (entityModelManage refuses a token). */
    public static final Set<String> TX_ROUTED = Set.of("entityManage", "entityManageCollection", "entitySearch", "entitySearchCollection");

    private static final Logger LOG = LoggerFactory.getLogger(CyodaCallInterceptor.class);
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TX_TOKEN = Metadata.Key.of("tx-token", Metadata.ASCII_STRING_MARSHALLER);

    private final CyodaTokenSource tokenSource;

    public CyodaCallInterceptor(CyodaTokenSource tokenSource) {
        this.tokenSource = tokenSource;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        CyodaCallContext ctx = options.getOption(CONTEXT);
        if (ctx == null) {
            LOG.warn("No CyodaCallContext on {}; cancelling with FAILED_PRECONDITION", method.getFullMethodName());
            return new FailedClientCall<>(Status.FAILED_PRECONDITION.withDescription(
                    "no CyodaCallContext on " + method.getFullMethodName() + "; use the framework's services"));
        }

        final String authorizationHeader;
        try {
            authorizationHeader = resolveAuthorizationHeader(ctx);
        } catch (RuntimeException e) {
            // Never log the token; the exception comes from the token source or a blank check and carries no token.
            LOG.warn("Cannot obtain a credential for {}; failing the call with UNAUTHENTICATED: {}",
                    method.getFullMethodName(), e.toString());
            return new FailedClientCall<>(
                    // Generic description: it can reach a REST client; the cause is for server-side logs only.
                    Status.UNAUTHENTICATED.withDescription("failed to obtain a Cyoda token").withCause(e));
        }

        boolean attachTxToken = ctx.isJoined() && TX_ROUTED.contains(method.getBareMethodName());
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (authorizationHeader != null) {
                    headers.put(AUTHORIZATION, authorizationHeader);
                }
                if (attachTxToken) {
                    headers.put(TX_TOKEN, ctx.txToken());
                }
                super.start(responseListener, headers);
            }
        };
    }

    /** Returns the {@code authorization} header value, or {@code null} when no header should be sent. */
    private String resolveAuthorizationHeader(CyodaCallContext ctx) {
        return switch (ctx.credential()) {
            case CyodaCallContext.None ignored -> null;
            case CyodaCallContext.M2m ignored -> {
                String token = tokenSource.bearerToken()
                        .orElseThrow(() -> new IllegalStateException("no M2M token source is configured"));
                CyodaGrpcCalls.recordSentM2mToken(token);
                yield "Bearer " + token;
            }
            case CyodaCallContext.Forward forward -> {
                if (forward.token() == null || forward.token().isBlank()) {
                    throw new IllegalStateException("forwarded token is blank");
                }
                yield "Bearer " + forward.token();
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
