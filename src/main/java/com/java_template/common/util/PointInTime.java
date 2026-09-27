package com.java_template.common.util;

import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * ABOUTME: The one conversion from the legacy millisecond {@link Date} point-in-time API to the
 * {@link OffsetDateTime} the framework and the cyoda-go wire use. Prefer the OffsetDateTime overloads:
 * a Date cannot carry the nanosecond precision of {@code EntityChangeMeta.timeOfChange}, so reading
 * as-at a truncated change time can miss that change.
 */
public final class PointInTime {

    private PointInTime() {
    }

    /** {@code null} stays {@code null} (current state); otherwise the same instant at UTC. */
    @Nullable
    public static OffsetDateTime toOffsetDateTime(@Nullable final Date date) {
        return date == null ? null : date.toInstant().atOffset(ZoneOffset.UTC);
    }
}
