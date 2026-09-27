package com.java_template.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** ABOUTME: Applies {@link CyodaJackson} to Spring Boot's auto-configured ObjectMapper. */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
public class CyodaJacksonAutoConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer cyodaJacksonCustomizer() {
        return builder -> builder.postConfigurer(CyodaJackson::configure);
    }
}
