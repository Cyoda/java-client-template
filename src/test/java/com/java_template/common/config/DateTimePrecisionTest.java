package com.java_template.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cyoda.cloud.api.event.common.EntityChangeMeta;
import org.cyoda.cloud.api.event.search.EntityGetRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cyoda-go emits date-time fields with full nanosecond precision (Go's time.RFC3339Nano). Since
 * cyoda-go v0.9 compares point-in-time reads at native precision with no millisecond rounding
 * (cyoda-go changelog, issue #349), any client-side truncation of that timestamp — e.g. round
 * -tripping it through java.util.Date, which only carries milliseconds — makes an exact-instant
 * reload of the entity's own just-committed transaction miss it (ENTITY_NOT_FOUND). This test
 * proves the production mapper carries a server timestamp through losslessly end to end: the
 * value it reads out of one response DTO, once written into another request DTO, serializes back
 * to the exact same text.
 */
class DateTimePrecisionTest {

    private final ObjectMapper om = CyodaJackson.configure(new ObjectMapper());

    @Test
    void survivesANanosecondPreciseTimeOfChangeVerbatim() throws Exception {
        String nanosPreciseInstant = "2026-09-27T10:11:12.123456789Z";
        String json = "{\"timeOfChange\":\"" + nanosPreciseInstant + "\",\"changeType\":\"CREATE\",\"user\":\"u\"}";

        EntityChangeMeta changeMeta = om.readValue(json, EntityChangeMeta.class);

        EntityGetRequest getRequest = new EntityGetRequest().withPointInTime(changeMeta.getTimeOfChange());
        String written = om.writeValueAsString(getRequest);

        assertThat(written).contains("\"pointInTime\":\"" + nanosPreciseInstant + "\"");
    }
}
