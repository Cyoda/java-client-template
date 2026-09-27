package com.java_template.testing.cyoda;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Placeholder completed in Task 13 (adds @SpringBootTest and the context customizer). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(CyodaServerExtension.class)
public @interface CyodaIntegrationTest {
    String profile() default CyodaProfiles.DEFAULT;
}
