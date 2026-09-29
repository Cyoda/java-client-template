package com.java_template.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * ABOUTME: Holder for the framework's own wire ObjectMapper, used for every Cyoda payload and event/OpenAPI DTO.
 * <p>
 * It is a copy of the app's primary mapper (so the app's modules and naming for its entity classes carry over)
 * with {@link CyodaJackson#configure} applied on top: JavaTimeModule, WRITE_DATES_AS_TIMESTAMPS off, and the
 * blank-processor-type handler. The app's own mapper is never modified. This is deliberately not an
 * {@link ObjectMapper} bean: a second ObjectMapper bean would make Spring Boot's JacksonAutoConfiguration back off.
 */
public final class CyodaObjectMapper {

    private final ObjectMapper mapper;

    private CyodaObjectMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A wire mapper built from a copy of {@code base}; {@code base} itself is left untouched. */
    public static CyodaObjectMapper from(ObjectMapper base) {
        Objects.requireNonNull(base, "base");
        return new CyodaObjectMapper(CyodaJackson.configure(base.copy()));
    }

    /** A wire mapper with Jackson defaults plus the Cyoda requirements, for tests and tools without Spring. */
    public static CyodaObjectMapper standalone() {
        return from(new ObjectMapper());
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
