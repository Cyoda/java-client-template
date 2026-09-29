package com.java_template.common.workflow;

import io.cloudevents.v1.proto.CloudEvent;
import org.cyoda.cloud.api.event.common.BaseEvent;

/**
 * ABOUTME: Context interface for passing CloudEvent and BaseEvent data to
 * CyodaProcessor and CyodaCriterion components during workflow execution.
 * Provides access to both the raw CloudEvent and the typed BaseEvent for
 * workflow processing operations.
 * @param <T> the type of BaseEvent (EntityProcessorCalculationRequest or EntityCriteriaCalculationRequest)
 */
public interface CyodaEventContext<T extends BaseEvent>  {

    /**
     * Gets the raw CloudEvent containing the event metadata and headers.
     * @return the CloudEvent instance
     */
    CloudEvent getCloudEvent();

    /**
     * Gets the typed BaseEvent containing the request data.
     * @return the typed BaseEvent (EntityProcessorCalculationRequest or EntityCriteriaCalculationRequest)
     */
    T getEvent();

    /** The callout's transaction token (CloudEvent attribute cyodatxtoken), or null when the dispatch carries none. */
    default String txToken() {
        var attr = getCloudEvent().getAttributesMap().get("cyodatxtoken");
        return attr == null ? null : attr.getCeString();
    }

    /** The originating principal as data (never a credential); empty when absent or unknown. */
    default com.java_template.common.auth.CloudEventAuthContext authContext() {
        return com.java_template.common.auth.CloudEventAuthContextExtractor.from(getCloudEvent());
    }
}
