package com.java_template.common.auth;

import com.java_template.common.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/** ABOUTME: Condition behind {@link ConditionalOnAuthMode}: binds app.config.auth-mode to the AuthMode enum. */
class OnAuthModeCondition extends SpringBootCondition {

    static final String PROPERTY = "app.config.auth-mode";
    /** Same default as {@link Config#getAuthMode()}. */
    static final Config.AuthMode DEFAULT = Config.AuthMode.CLIENT_CREDENTIALS;

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnAuthMode.class.getName());
        Config.AuthMode required = (Config.AuthMode) attributes.get("value");
        Config.AuthMode actual = Binder.get(context.getEnvironment())
                .bind(PROPERTY, Config.AuthMode.class)
                .orElse(DEFAULT);
        String message = PROPERTY + " is " + actual + " (required " + required + ")";
        return actual == required ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
    }
}
