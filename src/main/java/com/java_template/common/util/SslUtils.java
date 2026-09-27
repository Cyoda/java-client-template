package com.java_template.common.util;

// ABOUTME: SSL utility class for configuring custom trust managers and SSL contexts
// ABOUTME: Provides methods to create HTTP clients that can trust specific hosts with self-signed certificates

import com.java_template.common.config.Config;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SslUtils {
    private static final Logger logger = LoggerFactory.getLogger(SslUtils.class);

    /**
     * Custom TrustManager that trusts all certificates when configured to do so
     */
    private static class PermissiveTrustManager implements X509TrustManager {
        private final X509TrustManager defaultTrustManager;
        private final boolean trustAll;

        public PermissiveTrustManager(boolean trustAll) throws Exception {
            this.trustAll = trustAll;

            if (!trustAll) {
                // Get the default trust manager for standard validation
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((java.security.KeyStore) null);
                this.defaultTrustManager = (X509TrustManager) tmf.getTrustManagers()[0];
            } else {
                this.defaultTrustManager = null;
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            if (!trustAll && defaultTrustManager != null) {
                try {
                    defaultTrustManager.checkClientTrusted(chain, authType);
                } catch (Exception e) {
                    logger.warn("Client certificate validation failed: {}", e.getMessage());
                }
            }
            // If trustAll is true, we skip validation
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            if (!trustAll && defaultTrustManager != null) {
                try {
                    defaultTrustManager.checkServerTrusted(chain, authType);
                } catch (Exception e) {
                    logger.error("Server certificate validation failed: {}", e.getMessage());
                    throw new RuntimeException("Certificate validation failed", e);
                }
            }
            // If trustAll is true, we skip validation
            logger.debug("Trusting server certificate (trustAll={})", trustAll);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            if (defaultTrustManager != null) {
                return defaultTrustManager.getAcceptedIssuers();
            }
            return new X509Certificate[0];
        }
    }

    /**
     * Custom HostnameVerifier that allows specific hosts
     */
    private static class SelectiveHostnameVerifier implements HostnameVerifier {
        private final List<String> trustedHosts;
        private final HostnameVerifier defaultVerifier;

        public SelectiveHostnameVerifier(List<String> trustedHosts) {
            this.trustedHosts = trustedHosts;
            this.defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        }

        @Override
        public boolean verify(String hostname, SSLSession session) {
            // Check if this host should be trusted
            if (isTrustedHost(hostname)) {
                logger.debug("Trusting hostname: {}", hostname);
                return true;
            }

            // Use default verification for other hosts
            boolean defaultResult = defaultVerifier.verify(hostname, session);
            if (!defaultResult) {
                logger.debug("Default hostname verification failed for: {}", hostname);
            }
            return defaultResult;
        }

        private boolean isTrustedHost(String hostname) {
            // Check exact match first
            if (trustedHosts.contains(hostname)) {
                return true;
            }

            // Check if hostname matches any trusted host without port
            for (String trusted : trustedHosts) {
                String trustedHost = trusted.split(":")[0];
                if (hostname.equals(trustedHost)) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Trust manager for {@code app.config.ssl-trusted-hosts}: a server certificate is accepted without validation
     * only when the peer host (the name the connection was opened with) is listed. Every other host, and any
     * check where the host is unknown, gets the JDK default validation, including hostname verification.
     */
    static final class TrustedHostsTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager defaultTrustManager;
        private final List<String> trustedHosts;

        TrustedHostsTrustManager(X509ExtendedTrustManager defaultTrustManager, List<String> trustedHosts) {
            this.defaultTrustManager = defaultTrustManager;
            this.trustedHosts = List.copyOf(trustedHosts);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            String host = engine == null ? null : engine.getPeerHost();
            if (isListedHost(host, trustedHosts)) {
                logger.debug("Trusting the certificate of listed host {}", host);
                return;
            }
            defaultTrustManager.checkServerTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            String host = null;
            if (socket instanceof SSLSocket sslSocket && sslSocket.getHandshakeSession() != null) {
                host = sslSocket.getHandshakeSession().getPeerHost();
            }
            if (isListedHost(host, trustedHosts)) {
                logger.debug("Trusting the certificate of listed host {}", host);
                return;
            }
            defaultTrustManager.checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // No connection, so no host to match: always the default validation.
            defaultTrustManager.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            defaultTrustManager.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            defaultTrustManager.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            defaultTrustManager.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return defaultTrustManager.getAcceptedIssuers();
        }
    }

    /** True when {@code host} is one of {@code trustedHosts}, compared case-insensitively and ignoring a listed port. */
    static boolean isListedHost(String host, List<String> trustedHosts) {
        if (host == null || host.isBlank()) {
            return false;
        }
        for (String trusted : trustedHosts) {
            if (host.equalsIgnoreCase(trusted) || host.equalsIgnoreCase(withoutPort(trusted))) {
                return true;
            }
        }
        return false;
    }

    private static String withoutPort(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            int end = hostAndPort.indexOf(']');
            return end > 0 ? hostAndPort.substring(1, end) : hostAndPort;
        }
        int colon = hostAndPort.indexOf(':');
        return colon >= 0 && colon == hostAndPort.lastIndexOf(':') ? hostAndPort.substring(0, colon) : hostAndPort;
    }

    private static X509ExtendedTrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager extended) {
                return extended;
            }
        }
        throw new IllegalStateException("No default X509ExtendedTrustManager");
    }

    /**
     * Creates an SSLContext based on configuration: trust-all with {@code ssl-trust-all}; certificate checks
     * relaxed for the {@code ssl-trusted-hosts} only; otherwise the JDK default.
     */
    public static SSLContext createSelectiveSSLContext(Config config) throws NoSuchAlgorithmException {
        List<String> trustedHosts = config.getTrustedHosts();

        if (config.isSslTrustAll()) {
            logger.warn("SSL_TRUST_ALL is enabled - this should only be used in development!");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new PermissiveTrustManager(true)}, new SecureRandom());
                return sslContext;
            } catch (Exception e) {
                logger.error("Failed to create permissive SSL context, falling back to default: {}", e.getMessage());
                return SSLContext.getDefault();
            }
        }

        if (trustedHosts.isEmpty()) {
            logger.debug("Using default SSL context - no trusted hosts configured");
            return SSLContext.getDefault();
        }

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new TrustedHostsTrustManager(defaultTrustManager(), trustedHosts)},
                    new SecureRandom());
            logger.info("SSL certificate checks relaxed for the listed hosts only: {}", trustedHosts);
            return sslContext;
        } catch (Exception e) {
            logger.error("Failed to create the trusted-hosts SSL context, falling back to default: {}", e.getMessage());
            return SSLContext.getDefault();
        }
    }

    /**
     * Creates an SSLContext that trusts all certificates (DEVELOPMENT ONLY)
     */
    @SuppressWarnings("unused")
    private static SSLContext createTrustAllSSLContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }

    /**
     * Connect timeout of every HttpClient built here (REST calls and the M2M token fetch): an unreachable Cyoda
     * fails fast rather than hanging a caller (or, for the token, every caller waiting on it).
     */
    public static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(10);

    /**
     * Creates a Java 11+ HttpClient with custom SSL configuration and a {@link #CONNECT_TIMEOUT}
     */
    public static java.net.http.HttpClient createHttpClient(Config config) {
        try {
            SSLContext sslContext = createSelectiveSSLContext(config);
            java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(CONNECT_TIMEOUT);

            List<String> trustedHosts = config.getTrustedHosts();
            if (config.isSslTrustAll() || !trustedHosts.isEmpty()) {
                logger.info("HttpClient configured with custom SSL settings for hosts: {}", trustedHosts);
            }

            return builder.build();
        } catch (Exception e) {
            logger.error("Failed to create HttpClient with custom SSL, using default: {}", e.getMessage());
            return java.net.http.HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        }
    }

    /**
     * Creates an Apache HttpClient with custom SSL configuration
     */
    public static CloseableHttpClient createApacheHttpClient(Config config) {
        try {
            SSLContext sslContext = createSelectiveSSLContext(config);
            List<String> trustedHosts = config.getTrustedHosts();

            HostnameVerifier hostnameVerifier;
            if (config.isSslTrustAll()) {
                hostnameVerifier = NoopHostnameVerifier.INSTANCE;
                logger.warn("Using NoopHostnameVerifier - this should only be used in development!");
            } else if (!trustedHosts.isEmpty()) {
                hostnameVerifier = new SelectiveHostnameVerifier(trustedHosts);
                logger.info("Using selective hostname verifier for hosts: {}", trustedHosts);
            } else {
                hostnameVerifier = SSLConnectionSocketFactory.getDefaultHostnameVerifier();
            }

            SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                    sslContext, hostnameVerifier);

            return HttpClients.custom()
                    .setSSLSocketFactory(sslSocketFactory)
                    .build();
        } catch (Exception e) {
            logger.error("Failed to create Apache HttpClient with custom SSL, using default: {}", e.getMessage());
            return HttpClients.createDefault();
        }
    }

    /**
     * Creates a gRPC ManagedChannelBuilder with custom SSL configuration and performance tuning
     */
    public static ManagedChannelBuilder<?> createGrpcChannelBuilder(
            final String host,
            final int port,
            final boolean avoidSsl,
            final Config config
    ) {
        try {
            NettyChannelBuilder channelBuilder;

            if (!avoidSsl && (config.isSslTrustAll() || shouldTrustHost(host, config))) {
                logger.info("Configuring gRPC channel to trust host: {} (self-signed certificates allowed)", host);

                // Create an SSL context that trusts all certificates
                if (config.isSslTrustAll()) {
                    logger.warn("Creating gRPC channel with InsecureTrustManagerFactory - DEVELOPMENT ONLY!");
                } else {
                    // For specific trusted hosts, we still use the insecure trust manager
                    // In a production environment, you might want to implement a more sophisticated approach
                    logger.info("Creating gRPC channel with relaxed SSL for trusted host: {}", host);
                }
                final SslContext sslContext = GrpcSslContexts.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();

                channelBuilder = NettyChannelBuilder.forAddress(host, port).sslContext(sslContext);
            } else {
                if (avoidSsl) {
                    logger.info("Skip using security for host: {}", host);
                    channelBuilder = NettyChannelBuilder.forAddress(host, port).usePlaintext();
                } else {
                    logger.debug("Using default transport security for host: {}", host);
                    channelBuilder = NettyChannelBuilder.forAddress(host, port);
                }
            }

            // Apply performance tuning parameters to handle high-volume operations
            channelBuilder
                    .maxInboundMessageSize(config.getGrpcMaxInboundMessageSize())
                    .maxInboundMetadataSize(config.getGrpcMaxInboundMetadataSize())
                    .keepAliveTime(config.getGrpcKeepAliveTimeSeconds(), TimeUnit.SECONDS)
                    .keepAliveTimeout(config.getGrpcKeepAliveTimeoutSeconds(), TimeUnit.SECONDS)
                    .idleTimeout(config.getGrpcIdleTimeoutSeconds(), TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)  // Keep connection alive even without active calls
                    // CRITICAL: Set HTTP/2 flow control window to handle burst traffic
                    // This prevents RST_STREAM errors when 1000+ workflow events arrive simultaneously
                    .flowControlWindow(config.getGrpcFlowControlWindow())
                    .initialFlowControlWindow(config.getGrpcFlowControlWindow());  // Set initial window size too

            logger.info("gRPC channel configured: maxInboundMessageSize={}MB, flowControlWindow={}MB, initialFlowControlWindow={}MB, keepAliveTime={}s, keepAliveWithoutCalls=true, callouts={}",
                    config.getGrpcMaxInboundMessageSize() / (1024 * 1024),
                    config.getGrpcFlowControlWindow() / (1024 * 1024),
                    config.getGrpcFlowControlWindow() / (1024 * 1024),
                    config.getGrpcKeepAliveTimeSeconds(),
                    "virtual".equals(config.getExecutionMode())
                            ? "one virtual thread per task"
                            : "threadPools[processor=" + config.getProcessorThreadPool()
                                    + ", criteria=" + config.getCriteriaThreadPool() + "]");

            return channelBuilder;
        } catch (Exception e) {
            logger.error(
                    "Failed to configure gRPC SSL for {}:{}, falling back to default: {}",
                    host,
                    port,
                    e.getMessage()
            );
            NettyChannelBuilder fallbackBuilder;
            if (avoidSsl) {
                fallbackBuilder = NettyChannelBuilder.forAddress(host, port).usePlaintext();
            } else {
                fallbackBuilder = NettyChannelBuilder.forAddress(host, port);
            }
            // Still apply performance tuning even in fallback case
            return fallbackBuilder
                    .maxInboundMessageSize(config.getGrpcMaxInboundMessageSize())
                    .maxInboundMetadataSize(config.getGrpcMaxInboundMetadataSize())
                    .flowControlWindow(config.getGrpcFlowControlWindow())
                    .initialFlowControlWindow(config.getGrpcFlowControlWindow())
                    .keepAliveTime(config.getGrpcKeepAliveTimeSeconds(), TimeUnit.SECONDS)
                    .keepAliveTimeout(config.getGrpcKeepAliveTimeoutSeconds(), TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .idleTimeout(config.getGrpcIdleTimeoutSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * Check if a host should be trusted based on configuration
     */
    public static boolean shouldTrustHost(String host, Config config) {
        if (config.isSslTrustAll()) {
            logger.debug("Trusting host {} due to SSL_TRUST_ALL=true", host);
            return true;
        }

        List<String> trustedHosts = config.getTrustedHosts();
        if (trustedHosts.isEmpty()) {
            return false;
        }

        // Check exact match first
        if (trustedHosts.contains(host)) {
            logger.debug("Host {} found in trusted hosts list", host);
            return true;
        }

        // Check host without port
        String hostWithoutPort = host.split(":")[0];
        if (trustedHosts.contains(hostWithoutPort)) {
            logger.debug("Host {} (without port) found in trusted hosts list", hostWithoutPort);
            return true;
        }

        // Check if any trusted host matches this host (with or without port)
        for (String trustedHost : trustedHosts) {
            String trustedHostWithoutPort = trustedHost.split(":")[0];
            if (hostWithoutPort.equals(trustedHostWithoutPort)) {
                logger.debug("Host {} matches trusted host {} (ignoring ports)", host, trustedHost);
                return true;
            }
        }

        logger.debug("Host {} not found in trusted hosts: {}", host, trustedHosts);
        return false;
    }
}
