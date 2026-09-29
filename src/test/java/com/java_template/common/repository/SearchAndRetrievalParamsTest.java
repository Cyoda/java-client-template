package com.java_template.common.repository;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** ABOUTME: SearchAndRetrievalParams keeps an OffsetDateTime point in time at full precision; Date still works. */
class SearchAndRetrievalParamsTest {

    @Test
    void offsetDateTimePointInTimeIsStoredWithFullPrecision() {
        OffsetDateTime pit = OffsetDateTime.parse("2026-09-27T10:11:12.123456789+02:00");

        SearchAndRetrievalParams params = SearchAndRetrievalParams.builder().pointInTime(pit).build();

        assertThat(params.pointInTime()).isSameAs(pit);
    }

    @Test
    void datePointInTimeStillWorksAndIsStoredAsUtc() {
        Date pit = new Date(1_790_000_000_123L);

        SearchAndRetrievalParams params = SearchAndRetrievalParams.builder().pointInTime(pit).build();

        assertThat(params.pointInTime()).isEqualTo(pit.toInstant().atOffset(ZoneOffset.UTC));
    }

    @Test
    void nullDateMeansCurrentState() {
        SearchAndRetrievalParams params = SearchAndRetrievalParams.builder().pointInTime((Date) null).build();

        assertThat(params.pointInTime()).isNull();
    }
}
