package com.java_template.common.config;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
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
