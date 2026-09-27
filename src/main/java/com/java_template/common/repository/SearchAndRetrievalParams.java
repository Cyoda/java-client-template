package com.java_template.common.repository;

import com.java_template.common.util.PointInTime;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * Record class encapsulating search and retrieval parameters for repository queries.
 * Provides a builder pattern for flexible parameter construction.
 *<p>
 * @param pageSize Number of entities per page (default: 100)
 * @param pageNumber Page number, 0-based (default: 0)
 * @param inMemory Whether to perform search in memory (default: false)
 * @param awaitLimitMs Maximum time to wait for snapshot creation in milliseconds (default: 10000)
 * @param pollIntervalMs Polling interval for snapshot status checks in milliseconds (default: 500)
 * @param pointInTime Optional point in time for historical data retrieval, at full (nanosecond) precision
 * @param searchId Optional search identifier for getting further pages of the same search
 */
public record SearchAndRetrievalParams(
        int pageSize,
        int pageNumber,
        boolean inMemory,
        int awaitLimitMs,
        int pollIntervalMs,
        @Nullable OffsetDateTime pointInTime,
        @Nullable UUID searchId
) {

    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int FIRST_PAGE = 0;
    public static final boolean DEFAULT_IN_MEMORY = false;
    public static final int DEFAULT_AWAIT_LIMIT_MS = 10000;
    public static final int DEFAULT_POLL_INTERVAL_MS = 500;

    /**
     * Creates a new builder instance.
     *
     * @return A new SearchAndRetrievalParams.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates default search and retrieval parameters.
     *
     * @return SearchAndRetrievalParams with default values
     */
    public static SearchAndRetrievalParams defaults() {
        return new SearchAndRetrievalParams(
                DEFAULT_PAGE_SIZE,
                FIRST_PAGE,
                DEFAULT_IN_MEMORY,
                DEFAULT_AWAIT_LIMIT_MS,
                DEFAULT_POLL_INTERVAL_MS,
                null,
                null
        );
    }

    /**
     * Builder class for constructing SearchAndRetrievalParams instances.
     */
    public static class Builder {
        private int pageSize = DEFAULT_PAGE_SIZE;
        private int pageNumber = FIRST_PAGE;
        private boolean inMemory = DEFAULT_IN_MEMORY;
        private int awaitLimitMs = DEFAULT_AWAIT_LIMIT_MS;
        private int pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        private OffsetDateTime pointInTime = null;
        private UUID searchId = null;

        private Builder() {
        }

        /**
         * Sets the page size.
         *
         * @param pageSize Number of entities per page
         * @return This builder instance
         */
        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        /**
         * Sets the page number.
         *
         * @param pageNumber Page number (0-based)
         * @return This builder instance
         */
        public Builder pageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
            return this;
        }

        /**
         * Sets whether to perform search in memory.
         *
         * @param inMemory Whether to perform search in memory
         * @return This builder instance
         */
        public Builder inMemory(boolean inMemory) {
            this.inMemory = inMemory;
            return this;
        }

        /**
         * Sets the maximum time to wait for snapshot creation.
         *
         * @param awaitLimitMs Maximum time to wait in milliseconds
         * @return This builder instance
         */
        public Builder awaitLimitMs(int awaitLimitMs) {
            this.awaitLimitMs = awaitLimitMs;
            return this;
        }

        /**
         * Sets the polling interval for snapshot status checks.
         *
         * @param pollIntervalMs Polling interval in milliseconds
         * @return This builder instance
         */
        public Builder pollIntervalMs(int pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
            return this;
        }

        /**
         * Sets the point in time for historical data retrieval, keeping its full precision.
         * Use this overload for a value taken from {@code EntityChangeMeta.getTimeOfChange()}.
         *
         * @param pointInTime Point in time for historical data retrieval (null for current state)
         * @return This builder instance
         */
        public Builder pointInTime(@Nullable OffsetDateTime pointInTime) {
            this.pointInTime = pointInTime;
            return this;
        }

        /**
         * Sets the point in time for historical data retrieval from a millisecond {@link Date}.
         * Prefer {@link #pointInTime(OffsetDateTime)}: a Date cannot carry sub-millisecond precision.
         *
         * @param pointInTime Timestamp for historical data retrieval (null for current state)
         * @return This builder instance
         */
        public Builder pointInTime(@Nullable Date pointInTime) {
            this.pointInTime = PointInTime.toOffsetDateTime(pointInTime);
            return this;
        }

        /**
         * Sets the search identifier for getting further pages of the same search.
         *
         * @param searchId Search identifier
         * @return This builder instance
         */
        public Builder searchId(@Nullable UUID searchId) {
            this.searchId = searchId;
            return this;
        }

        /**
         * Builds the SearchAndRetrievalParams instance.
         *
         * @return A new SearchAndRetrievalParams instance
         */
        public SearchAndRetrievalParams build() {
            return new SearchAndRetrievalParams(pageSize, pageNumber, inMemory, awaitLimitMs, pollIntervalMs, pointInTime, searchId);
        }
    }
}

