package com.java_template.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * ABOUTME: Provides the framework's wire mapper ({@link CyodaObjectMapper}), built from a copy of the app's
 * primary ObjectMapper. The app's primary mapper is not modified.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
public class CyodaJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CyodaObjectMapper cyodaObjectMapper(ObjectProvider<ObjectMapper> appMapper) {
        return CyodaObjectMapper.from(appMapper.getIfUnique(ObjectMapper::new));
    }
}
