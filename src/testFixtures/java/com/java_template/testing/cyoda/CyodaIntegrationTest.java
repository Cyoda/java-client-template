package com.java_template.testing.cyoda;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots the application (found by @SpringBootConfiguration search) against a cyoda-go
 * subprocess for the given profile, with auth-mode=none and a context-unique processor tag.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(CyodaServerExtension.class)
@SpringBootTest
public @interface CyodaIntegrationTest {
    String profile() default CyodaProfiles.DEFAULT;
}
