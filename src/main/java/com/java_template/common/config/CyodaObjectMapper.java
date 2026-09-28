package com.java_template.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.Objects;

/**
 * ABOUTME: The framework's two Jackson mappers (spec §4.9): one for Cyoda protocol messages, one for the app's
 * entities.
 * <ul>
 *   <li>{@link #protocol()}: every Cyoda protocol message: CloudEvent payload JSON, the event DTOs
 *   ({@code org.cyoda.cloud.api.event.*}), the OpenAPI models ({@code org.cyoda.cloud.api.common.model}),
 *   workflow JSON and REST bodies sent to Cyoda, and {@code DataPayload} envelopes, including reading and writing
 *   the entity JSON tree inside them. It is fixed: {@link CyodaJackson#configure} on a new {@link ObjectMapper},
 *   with {@code FAIL_ON_UNKNOWN_PROPERTIES} off so a field cyoda-go adds never breaks parsing. No
 *   {@code spring.jackson.*} setting and no app bean reaches it.</li>
 *   <li>{@link #entities()}: converting between the app's entity classes and JSON trees. It is the app's primary
 *   {@code ObjectMapper}, used as is: not copied, not modified. The entity JSON stored in Cyoda therefore follows
 *   the app's Jackson settings (with {@code SNAKE_CASE} naming, the entity is stored in snake_case).</li>
 * </ul>
 * This is deliberately not an {@link ObjectMapper} bean: a second ObjectMapper bean would make Spring Boot's
 * JacksonAutoConfiguration back off.
 */
public final class CyodaObjectMapper {

    private final ObjectMapper protocol;
    private final ObjectMapper entities;

    private CyodaObjectMapper(ObjectMapper protocol, ObjectMapper entities) {
        this.protocol = protocol;
        this.entities = entities;
    }

    /** The framework's mappers, with {@code appMapper} (left untouched) for entities. */
    public static CyodaObjectMapper of(ObjectMapper appMapper) {
        Objects.requireNonNull(appMapper, "appMapper");
        return new CyodaObjectMapper(newProtocolMapper(), appMapper);
    }

    /**
     * For tests and tools without Spring: the entity mapper is the one Spring Boot's JacksonAutoConfiguration builds
     * when the app sets no {@code spring.jackson.*} property (well-known modules such as JavaTimeModule,
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} off, date-times written as ISO-8601 text).
     */
    public static CyodaObjectMapper standalone() {
        return of(Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .build());
    }

    /** A new protocol mapper: {@link CyodaJackson#configure} plus {@code FAIL_ON_UNKNOWN_PROPERTIES} off. */
    public static ObjectMapper newProtocolMapper() {
        return CyodaJackson.configure(new ObjectMapper())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** The fixed mapper for every Cyoda protocol message. */
    public ObjectMapper protocol() {
        return protocol;
    }

    /** The app's primary mapper, for converting the app's entity classes to and from JSON trees. */
    public ObjectMapper entities() {
        return entities;
    }
}
