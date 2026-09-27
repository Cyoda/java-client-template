package com.java_template.common.auth;

import com.java_template.common.config.Config;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ABOUTME: Matches when {@code app.config.auth-mode} binds to the given {@link Config.AuthMode}. It binds the
 * property the way {@link Config} does, so every spelling Spring's enum binding accepts (client-credentials,
 * CLIENT_CREDENTIALS, …) selects the same bean; unset means {@link Config.AuthMode#CLIENT_CREDENTIALS}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnAuthModeCondition.class)
public @interface ConditionalOnAuthMode {
    Config.AuthMode value();
}
