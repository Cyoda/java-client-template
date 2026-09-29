package com.java_template.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * ABOUTME: Provides the framework's mappers ({@link CyodaObjectMapper}): the fixed protocol mapper, and a copy of
 * the app's primary ObjectMapper (with decimal scale always kept) for entities (spec §4.9). The app's own bean is
 * never modified. An app with no ObjectMapper, or with several and none primary, fails at startup rather than
 * silently getting a bare mapper.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
public class CyodaJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CyodaObjectMapper cyodaObjectMapper(ObjectProvider<ObjectMapper> appMapper) {
        try {
            return CyodaObjectMapper.of(appMapper.getObject());
        } catch (NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException("The Cyoda framework converts entities with the app's primary ObjectMapper, "
                    + "but the application context has " + e.getNumberOfBeansFound() + " ObjectMapper beans "
                    + e.getBeanNamesFound() + " and none is primary. Mark the one for entities (and your REST API) "
                    + "@Primary.");
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalStateException("The Cyoda framework converts entities with the app's primary ObjectMapper, "
                    + "but the application context has no ObjectMapper bean. Keep Spring Boot's "
                    + "JacksonAutoConfiguration enabled, or define an ObjectMapper bean.");
        }
    }
}
