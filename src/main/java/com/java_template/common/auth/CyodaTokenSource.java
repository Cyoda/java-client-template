package com.java_template.common.auth;

import java.util.Optional;

/** ABOUTME: Source of the bearer token for outbound Cyoda calls; empty when app.config.auth-mode=none. */
public interface CyodaTokenSource {

    Optional<String> bearerToken();

    /** Drops a cached token so the next call fetches a new one. */
    void invalidate();
}
