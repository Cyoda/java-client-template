package com.java_template.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
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
 *   <li>{@link #entities()}: converting between the app's entity classes and JSON trees. It is a copy of the
 *   app's primary {@code ObjectMapper}, taken once at startup, with only
 *   {@code JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES} disabled: every other app setting (naming, modules,
 *   date format) stays, and the app's own bean is never modified. Without this, a {@code BigDecimal} entity
 *   field's scale would be lost in the entity-to-tree conversion every write and processor response payload
 *   goes through ({@code valueToTree} / {@code entityToJsonNode}), even though the app's own JSON output
 *   (serializing a {@code BigDecimal} field directly, not through this tree) already keeps it. The entity JSON
 *   stored in Cyoda therefore follows the app's Jackson settings (with {@code SNAKE_CASE} naming, the entity is
 *   stored in snake_case), except that decimal scale is always kept.</li>
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

    /** The framework's mappers: {@code appMapper} untouched, and a decimal-scale-preserving copy of it for entities. */
    public static CyodaObjectMapper of(ObjectMapper appMapper) {
        Objects.requireNonNull(appMapper, "appMapper");
        return new CyodaObjectMapper(newProtocolMapper(), newEntityMapper(appMapper));
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

    /**
     * A copy of {@code appMapper} (the bean itself is never touched), with only
     * {@code JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES} disabled. {@link ObjectMapper#copy()} preserves
     * every other setting: naming strategy, registered modules, date format.
     */
    private static ObjectMapper newEntityMapper(ObjectMapper appMapper) {
        return appMapper.copy().configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    }

    /** The fixed mapper for every Cyoda protocol message. */
    public ObjectMapper protocol() {
        return protocol;
    }

    /**
     * A copy of the app's primary mapper, for converting the app's entity classes to and from JSON trees: the
     * app's own settings, except that decimal scale is always kept (see the class Javadoc).
     */
    public ObjectMapper entities() {
        return entities;
    }
}
