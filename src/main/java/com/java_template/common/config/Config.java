package com.java_template.common.config;

import java.util.Arrays;
import java.util.List;
import org.cyoda.cloud.api.event.common.DataFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ABOUTME: Central configuration class providing environment-based settings
 * for Cyoda platform connection, gRPC communication, and application parameters.
 */
@Component
@ConfigurationProperties(prefix = "app.config")
public class Config {

    private String cyodaHost;
    private String cyodaApiUrl;
    private String grpcAddress;
    private int grpcServerPort = 443;
    private String grpcProcessorTag = "cloud_manager_app";

    // gRPC Channel Configuration
    private int grpcMaxInboundMessageSize = 16777216; // 16MB
    private int grpcMaxInboundMetadataSize = 16384; // 16KB
    private int grpcFlowControlWindow = 67108864; // 64MB
    private long grpcKeepAliveTimeSeconds = 30;
    private long grpcKeepAliveTimeoutSeconds = 10;
    private long grpcIdleTimeoutSeconds = 300; // 5 minutes

    // Thread pool configurations
    private int processorThreadPool = 20;
    private int criteriaThreadPool = 20;
    private int controlThreadPool = 3;

    private int handshakeTimeoutMs = 5000;

    private int initialReconnectDelayMs = 200;
    private int maxReconnectDelayMs = 10000;
    private int failedReconnectsLimit = 10;

    private String cyodaClientId;
    private String cyodaClientSecret;

    private String grpcCommunicationDataFormat = DataFormat.JSON.value();

    // Monitoring
    private int sentEventsCacheMaxSize = 100;
    private int monitoringSchedulerInitialDelaySeconds = 1;
    private int monitoringSchedulerDelaySeconds = 3;
    private long keepAliveWarningThreshold = 20000;

    // SSL Configuration
    private boolean sslTrustAll = false;
    private String sslTrustedHosts = "";

    private boolean includeDefaultOperations = false;

    private String executionMode = "virtual";

    public enum AuthMode { CLIENT_CREDENTIALS, NONE }

    private boolean grpcTls = true;
    private AuthMode authMode = AuthMode.CLIENT_CREDENTIALS;
    private long grpcCallDeadlineMs = 120_000L;

    public boolean isGrpcTls() {
        return grpcTls;
    }

    public void setGrpcTls(boolean grpcTls) {
        this.grpcTls = grpcTls;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public long getGrpcCallDeadlineMs() {
        return grpcCallDeadlineMs;
    }

    public void setGrpcCallDeadlineMs(long grpcCallDeadlineMs) {
        this.grpcCallDeadlineMs = grpcCallDeadlineMs;
    }

    /** Base package scanned for {@link com.java_template.common.workflow.CyodaEntity} implementations. */
    private String entityBasePackage = "com.java_template.application";

    // Getters and setters

    public String getCyodaHost() {
        return cyodaHost;
    }

    public void setCyodaHost(String cyodaHost) {
        this.cyodaHost = cyodaHost;
        // Update dependent properties if not explicitly set
        if (cyodaApiUrl == null) {
            cyodaApiUrl = "https://" + cyodaHost + "/api";
        }
        if (grpcAddress == null) {
            grpcAddress = "grpc-" + cyodaHost;
        }
    }

    public String getCyodaApiUrl() {
        return cyodaApiUrl;
    }

    public void setCyodaApiUrl(String cyodaApiUrl) {
        this.cyodaApiUrl = cyodaApiUrl;
    }

    public String getGrpcAddress() {
        return grpcAddress;
    }

    public void setGrpcAddress(String grpcAddress) {
        this.grpcAddress = grpcAddress;
    }

    public int getGrpcServerPort() {
        return grpcServerPort;
    }

    public void setGrpcServerPort(int grpcServerPort) {
        this.grpcServerPort = grpcServerPort;
    }

    public String getGrpcProcessorTag() {
        return grpcProcessorTag;
    }

    public void setGrpcProcessorTag(String grpcProcessorTag) {
        this.grpcProcessorTag = grpcProcessorTag;
    }

    public int getGrpcMaxInboundMessageSize() {
        return grpcMaxInboundMessageSize;
    }

    public void setGrpcMaxInboundMessageSize(int grpcMaxInboundMessageSize) {
        this.grpcMaxInboundMessageSize = grpcMaxInboundMessageSize;
    }

    public int getGrpcMaxInboundMetadataSize() {
        return grpcMaxInboundMetadataSize;
    }

    public void setGrpcMaxInboundMetadataSize(int grpcMaxInboundMetadataSize) {
        this.grpcMaxInboundMetadataSize = grpcMaxInboundMetadataSize;
    }

    public int getGrpcFlowControlWindow() {
        return grpcFlowControlWindow;
    }

    public void setGrpcFlowControlWindow(int grpcFlowControlWindow) {
        this.grpcFlowControlWindow = grpcFlowControlWindow;
    }

    public long getGrpcKeepAliveTimeSeconds() {
        return grpcKeepAliveTimeSeconds;
    }

    public void setGrpcKeepAliveTimeSeconds(long grpcKeepAliveTimeSeconds) {
        this.grpcKeepAliveTimeSeconds = grpcKeepAliveTimeSeconds;
    }

    public long getGrpcKeepAliveTimeoutSeconds() {
        return grpcKeepAliveTimeoutSeconds;
    }

    public void setGrpcKeepAliveTimeoutSeconds(long grpcKeepAliveTimeoutSeconds) {
        this.grpcKeepAliveTimeoutSeconds = grpcKeepAliveTimeoutSeconds;
    }

    public long getGrpcIdleTimeoutSeconds() {
        return grpcIdleTimeoutSeconds;
    }

    public void setGrpcIdleTimeoutSeconds(long grpcIdleTimeoutSeconds) {
        this.grpcIdleTimeoutSeconds = grpcIdleTimeoutSeconds;
    }

    public int getProcessorThreadPool() {
        return processorThreadPool;
    }

    public void setProcessorThreadPool(int processorThreadPool) {
        this.processorThreadPool = processorThreadPool;
    }

    public int getCriteriaThreadPool() {
        return criteriaThreadPool;
    }

    public void setCriteriaThreadPool(int criteriaThreadPool) {
        this.criteriaThreadPool = criteriaThreadPool;
    }

    public int getControlThreadPool() {
        return controlThreadPool;
    }

    public void setControlThreadPool(int controlThreadPool) {
        this.controlThreadPool = controlThreadPool;
    }

    public int getHandshakeTimeoutMs() {
        return handshakeTimeoutMs;
    }

    public void setHandshakeTimeoutMs(int handshakeTimeoutMs) {
        this.handshakeTimeoutMs = handshakeTimeoutMs;
    }

    public int getInitialReconnectDelayMs() {
        return initialReconnectDelayMs;
    }

    public void setInitialReconnectDelayMs(int initialReconnectDelayMs) {
        this.initialReconnectDelayMs = initialReconnectDelayMs;
    }

    public int getMaxReconnectDelayMs() {
        return maxReconnectDelayMs;
    }

    public void setMaxReconnectDelayMs(int maxReconnectDelayMs) {
        this.maxReconnectDelayMs = maxReconnectDelayMs;
    }

    public int getFailedReconnectsLimit() {
        return failedReconnectsLimit;
    }

    public void setFailedReconnectsLimit(int failedReconnectsLimit) {
        this.failedReconnectsLimit = failedReconnectsLimit;
    }

    public String getCyodaClientId() {
        return cyodaClientId;
    }

    public void setCyodaClientId(String cyodaClientId) {
        this.cyodaClientId = cyodaClientId;
    }

    public String getCyodaClientSecret() {
        return cyodaClientSecret;
    }

    public void setCyodaClientSecret(String cyodaClientSecret) {
        this.cyodaClientSecret = cyodaClientSecret;
    }

    public DataFormat getGrpcCommunicationDataFormat() {
        return DataFormat.fromValue(grpcCommunicationDataFormat);
    }

    public void setGrpcCommunicationDataFormat(String grpcCommunicationDataFormat) {
        this.grpcCommunicationDataFormat = grpcCommunicationDataFormat;
    }

    public String getEventSourceUri() {
        return "urn:cyoda:calculation-member:" + grpcProcessorTag;
    }

    public int getSentEventsCacheMaxSize() {
        return sentEventsCacheMaxSize;
    }

    public void setSentEventsCacheMaxSize(int sentEventsCacheMaxSize) {
        this.sentEventsCacheMaxSize = sentEventsCacheMaxSize;
    }

    public int getMonitoringSchedulerInitialDelaySeconds() {
        return monitoringSchedulerInitialDelaySeconds;
    }

    public void setMonitoringSchedulerInitialDelaySeconds(int monitoringSchedulerInitialDelaySeconds) {
        this.monitoringSchedulerInitialDelaySeconds = monitoringSchedulerInitialDelaySeconds;
    }

    public int getMonitoringSchedulerDelaySeconds() {
        return monitoringSchedulerDelaySeconds;
    }

    public void setMonitoringSchedulerDelaySeconds(int monitoringSchedulerDelaySeconds) {
        this.monitoringSchedulerDelaySeconds = monitoringSchedulerDelaySeconds;
    }

    public long getKeepAliveWarningThreshold() {
        return keepAliveWarningThreshold;
    }

    public void setKeepAliveWarningThreshold(long keepAliveWarningThreshold) {
        this.keepAliveWarningThreshold = keepAliveWarningThreshold;
    }

    public boolean isSslTrustAll() {
        return sslTrustAll;
    }

    public void setSslTrustAll(boolean sslTrustAll) {
        this.sslTrustAll = sslTrustAll;
    }

    public String getSslTrustedHosts() {
        return sslTrustedHosts;
    }

    public void setSslTrustedHosts(String sslTrustedHosts) {
        this.sslTrustedHosts = sslTrustedHosts;
    }

    public boolean isIncludeDefaultOperations() {
        return includeDefaultOperations;
    }

    public void setIncludeDefaultOperations(boolean includeDefaultOperations) {
        this.includeDefaultOperations = includeDefaultOperations;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getEntityBasePackage() {
        return entityBasePackage;
    }

    public void setEntityBasePackage(String entityBasePackage) {
        this.entityBasePackage = entityBasePackage;
    }

    /**
     * Get list of hosts that should be trusted even with self-signed certificates
     * @return List of trusted hosts
     */
    public List<String> getTrustedHosts() {
        if (sslTrustedHosts == null || sslTrustedHosts.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sslTrustedHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .toList();
    }

}
