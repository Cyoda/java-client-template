package com.java_template.common.grpc.client.event_handling;

import io.cloudevents.v1.proto.CloudEvent;

/**
 * ABOUTME: Safe log summary of a CloudEvent. Never log a CloudEvent itself: its toString prints every
 * attribute, including a callout's {@code cyodatxtoken} (a bearer credential for the joined transaction) and
 * the caller's {@code authid}/{@code authclaims}.
 */
public final class CloudEvents {

    private CloudEvents() {
    }

    /**
     * The event's type, id and source, plus the requestId and entityId of its payload when they can be read.
     * No other attribute value is ever included.
     */
    public static String describe(CloudEvent cloudEvent) {
        if (cloudEvent == null) {
            return "CloudEvent[null]";
        }
        StringBuilder sb = new StringBuilder("CloudEvent[type=").append(cloudEvent.getType())
                .append(", id=").append(cloudEvent.getId())
                .append(", source=").append(cloudEvent.getSource());
        if (cloudEvent.hasTextData()) {
            AbstractEventStrategy.recoverRequestIdFromCloudEvent(cloudEvent).requestId()
                    .ifPresent(requestId -> sb.append(", requestId=").append(requestId));
            AbstractEventStrategy.recoverEntityIdFromCloudEvent(cloudEvent)
                    .ifPresent(entityId -> sb.append(", entityId=").append(entityId));
        }
        return sb.append(']').toString();
    }
}
