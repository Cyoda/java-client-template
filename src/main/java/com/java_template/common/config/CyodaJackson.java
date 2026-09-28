package com.java_template.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.cyoda.cloud.api.common.model.ExternalizedProcessorDefinitionDto;
import org.cyoda.cloud.api.common.model.ProcessorDefinitionDto;

/**
 * ABOUTME: Jackson settings the cyoda-go contract needs on every ObjectMapper that reads workflow DTOs.
 * A processor whose "type" is missing or blank is externalized, as cyoda-go treats it (spec §3.3.3).
 */
public final class CyodaJackson {

    private CyodaJackson() {
    }

    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.addHandler(new ProcessorTypeDefault());
        // cyoda-go's event DTOs (EntityChangeMeta.timeOfChange, EntityGetRequest.pointInTime, …)
        // are java.time.OffsetDateTime (see build.gradle's jsonSchema2Pojo dateTimeType), carrying
        // the same nanosecond precision cyoda-go emits on the wire (Go's time.RFC3339Nano). Without
        // JavaTimeModule, Jackson cannot (de)serialize OffsetDateTime at all. WRITE_DATES_AS_TIMESTAMPS
        // must stay off: JavaTimeModule's default with it enabled writes an OffsetDateTime as a
        // numeric [seconds, nanos] array, not the RFC3339 text cyoda-go's HTTP/gRPC contract expects,
        // which would break the wire format even though it wouldn't lose precision.
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // A BigDecimal entity field beyond ~17 significant digits loses precision if a decimal literal in the
        // payload's `data` tree parses as DoubleNode. This makes it parse as DecimalNode instead, losslessly.
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        // Two more settings are needed for that to be lossless in practice, on every tree this mapper parses
        // (entity data, REST responses, workflow JSON):
        // - Jackson 2.19 defaults to normalizing a BigDecimal by stripping trailing zeroes when building a
        //   DecimalNode, which also collapses its scale to a negative exponent (10.00 becomes 1E+1). Off, a
        //   DecimalNode keeps the exact scale it was parsed with.
        mapper.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        // - BigDecimal.toString() itself still switches to scientific notation once the adjusted exponent is
        //   past a threshold (e.g. 1e-7), regardless of the setting above. This makes the generator always use
        //   toPlainString() instead.
        mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        return mapper;
    }

    static final class ProcessorTypeDefault extends DeserializationProblemHandler {

        @Override
        public JavaType handleUnknownTypeId(DeserializationContext ctxt, JavaType baseType, String subTypeId,
                                            TypeIdResolver idResolver, String failureMsg) {
            return blank(subTypeId) && isProcessor(baseType) ? externalized(ctxt) : null;
        }

        @Override
        public JavaType handleMissingTypeId(DeserializationContext ctxt, JavaType baseType,
                                            TypeIdResolver idResolver, String failureMsg) {
            return isProcessor(baseType) ? externalized(ctxt) : null;
        }

        /**
         * The "type" discriminator is visible, so after a blank id resolves to externalized, the same
         * blank value is still bound to {@link ProcessorDefinitionDto.TypeEnum}, whose factory rejects it.
         */
        @Override
        public Object handleInstantiationProblem(DeserializationContext ctxt, Class<?> instClass, Object argument,
                                                 Throwable t) {
            if (instClass == ProcessorDefinitionDto.TypeEnum.class
                    && (argument == null || argument instanceof String s && s.isBlank())) {
                return ProcessorDefinitionDto.TypeEnum.EXTERNALIZED;
            }
            return NOT_HANDLED;
        }

        private static boolean blank(String id) {
            return id == null || id.isBlank();
        }

        private static boolean isProcessor(JavaType baseType) {
            return ProcessorDefinitionDto.class.isAssignableFrom(baseType.getRawClass());
        }

        private static JavaType externalized(DeserializationContext ctxt) {
            return ctxt.constructType(ExternalizedProcessorDefinitionDto.class);
        }
    }
}
