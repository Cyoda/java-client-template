package com.java_template.common.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** ABOUTME: Token source for cyoda-go mock IAM (app.config.auth-mode=none): no Authorization header is sent. */
@Component
@ConditionalOnProperty(name = "app.config.auth-mode", havingValue = "none")
public class NoCyodaAuthentication implements CyodaTokenSource {

    @Override
    public Optional<String> bearerToken() {
        return Optional.empty();
    }

    @Override
    public void invalidate() {
        // nothing cached
    }
}
