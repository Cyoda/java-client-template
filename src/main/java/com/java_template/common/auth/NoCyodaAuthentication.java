package com.java_template.common.auth;

import com.java_template.common.config.Config;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** ABOUTME: Token source for cyoda-go mock IAM (app.config.auth-mode=none): no Authorization header is sent. */
@Component
@ConditionalOnAuthMode(Config.AuthMode.NONE)
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
