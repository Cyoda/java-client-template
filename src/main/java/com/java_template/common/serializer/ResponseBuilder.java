package com.java_template.common.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.java_template.common.workflow.CyodaEntity;
import org.cyoda.cloud.api.event.common.Error;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityCriteriaCalculationResponse;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationRequest;
import org.cyoda.cloud.api.event.processing.EntityProcessorCalculationResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * ABOUTME: Simplified ResponseBuilder providing type-safe fluent API for crafting responses from requests.
 * Factory class for creating criterion and processor response builders with direct accessibility.
 */
public final class ResponseBuilder {

    private ResponseBuilder() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a criterion response builder for the given request.
     */
    public static CriterionResponseBuilder forCriterion(EntityCriteriaCalculationRequest request) {
        return new CriterionResponseBuilder(request);
    }

    /**
     * Creates a processor response builder for the given request.
     */
    public static ProcessorResponseBuilder forProcessor(EntityProcessorCalculationRequest request) {
        return new ProcessorResponseBuilder(request);
    }

    /**
     * Simplified criterion response builder.
     * Provides type-safe fluent API for criterion match and error responses.
     */
    public static final class CriterionResponseBuilder {
        private final EntityCriteriaCalculationRequest request;
        private boolean success = false;
        private boolean matches = false;
        private Error error;
        private List<String> warnings;

        CriterionResponseBuilder(EntityCriteriaCalculationRequest request) {
            this.request = request;
        }

        /**
         * Creates a successful match response.
         */
        public CriterionResponseBuilder withMatch() {
            this.success = true;
            this.matches = true;
            return this;
        }

        /**
         * Creates a successful non-match response.
         */
        public CriterionResponseBuilder withNonMatch() {
            this.success = true;
            this.matches = false;
            return this;
        }

        /**
         * Creates an error response.
         */
        public CriterionResponseBuilder withError(String code, String message) {
            this.success = false;
            this.matches = false;
            this.error = new Error();
            this.error.setCode(code);
            this.error.setMessage(message);
            return this;
        }

        /**
         * Creates an error response from an exception.
         */
        public CriterionResponseBuilder withError(String code, Exception exception) {
            return withError(code, exception.getMessage());
        }

        /**
         * Adds a warning to the response.
         */
        public CriterionResponseBuilder withWarning(String warning) {
            if (warnings == null) {
                warnings = new ArrayList<>();
            }
            warnings.add(warning);
            return this;
        }

        /**
         * Adds multiple warnings to the response.
         */
        public CriterionResponseBuilder withWarnings(List<String> warnings) {
            if (this.warnings == null) {
                this.warnings = new ArrayList<>();
            }
            this.warnings.addAll(warnings);
            return this;
        }

        /**
         * Builds the criteria calculation response.
         */
        public EntityCriteriaCalculationResponse build() {
            EntityCriteriaCalculationResponse response = new EntityCriteriaCalculationResponse();

            // Copy basic fields from request
            response.setId(java.util.UUID.randomUUID().toString());
            response.setRequestId(request.getRequestId());
            response.setEntityId(request.getEntityId());
            response.setSuccess(success);
            response.setMatches(matches);

            // Set warnings if present
            if (warnings != null && !warnings.isEmpty()) {
                response.setWarnings(new ArrayList<>(warnings));
            }

            // Set error information if present
            if (error != null) {
                response.setSuccess(false);
                response.setError(error);
            }

            return response;
        }
    }

    /**
     * Simplified processor response builder.
     * Provides type-safe fluent API for processor success and error responses.
     */
    public static final class ProcessorResponseBuilder {
        private final EntityProcessorCalculationRequest request;
        private boolean success = false;
        private JsonNode jsonData;
        private Error error;
        private String additionalErrorDetails;

        ProcessorResponseBuilder(EntityProcessorCalculationRequest request) {
            this.request = request;
        }

        /**
         * Creates a successful response with JSON data.
         */
        public ProcessorResponseBuilder withSuccess(JsonNode data) {
            this.success = true;
            this.jsonData = data;
            return this;
        }

        /**
         * Creates a successful response with entity data using a converter function.
         */
        public <T extends CyodaEntity> ProcessorResponseBuilder withSuccess(T entity, Function<T, JsonNode> converter) {
            this.success = true;
            this.jsonData = converter.apply(entity);
            return this;
        }

        /**
         * Creates an error response.
         */
        public ProcessorResponseBuilder withError(String code, String message) {
            this.success = false;
            this.error = new Error();
            this.error.setCode(code);
            this.error.setMessage(message);
            return this;
        }

        /**
         * Creates an error response from an exception.
         */
        public ProcessorResponseBuilder withError(String code, Exception exception) {
            return withError(code, exception.getMessage());
        }

        /**
         * Adds additional error details to existing error information.
         */
        public ProcessorResponseBuilder withAdditionalErrorDetails(String details) {
            this.additionalErrorDetails = details;
            return this;
        }

        /**
         * Builds the processor calculation response.
         */
        public EntityProcessorCalculationResponse build() {
            EntityProcessorCalculationResponse response = new EntityProcessorCalculationResponse();

            // Copy basic fields from request
            response.setId(java.util.UUID.randomUUID().toString());
            response.setRequestId(request.getRequestId());
            response.setEntityId(request.getEntityId());
            response.setSuccess(success);

            // TODO: Decide if this is an issue, since this is in theory risky, because we are not cloning the payload object.
            // Unfortunately, the jsonSchema2Pojo tool does not have a clone feature.
            // See: https://github.com/joelittlejohn/jsonschema2pojo/issues/230
            response.setPayload(request.getPayload());

            // Set payload data if we have JSON data
            if (jsonData != null) {
                response.getPayload().setData(jsonData);
            }

            // Set error information if present
            if (error != null) {
                if (additionalErrorDetails != null) {
                    error.setMessage(error.getMessage() + " - " + additionalErrorDetails);
                }
                response.setSuccess(false);
                response.setError(error);
            }

            return response;
        }
    }

}
