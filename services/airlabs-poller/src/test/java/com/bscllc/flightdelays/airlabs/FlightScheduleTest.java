package com.bscllc.flightdelays.airlabs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlightScheduleTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void serializationRoundtrip() throws Exception {
        FlightSchedule schedule = new FlightSchedule(
                "AA100", "BWI", "LAX",
                "2026-03-28T10:00:00+00:00", "2026-03-28T16:00:00+00:00",
                "2026-03-28T10:05:00+00:00", "2026-03-28T16:10:00+00:00",
                "active", 15
        );

        String json = mapper.writeValueAsString(schedule);
        FlightSchedule deserialized = mapper.readValue(json, FlightSchedule.class);

        assertEquals(schedule.flightIata(), deserialized.flightIata());
        assertEquals(schedule.depIata(), deserialized.depIata());
        assertEquals(schedule.arrIata(), deserialized.arrIata());
        assertEquals(schedule.depTime(), deserialized.depTime());
        assertEquals(schedule.arrTime(), deserialized.arrTime());
        assertEquals(schedule.depEstimated(), deserialized.depEstimated());
        assertEquals(schedule.arrEstimated(), deserialized.arrEstimated());
        assertEquals(schedule.status(), deserialized.status());
        assertEquals(schedule.delayedMin(), deserialized.delayedMin());
    }

    @Test
    void allFieldsPresentInJson() throws Exception {
        FlightSchedule schedule = new FlightSchedule(
                "AA100", "BWI", "LAX",
                "2026-03-28T10:00:00+00:00", "2026-03-28T16:00:00+00:00",
                "2026-03-28T10:05:00+00:00", "2026-03-28T16:10:00+00:00",
                "active", 15
        );

        String json = mapper.writeValueAsString(schedule);

        assertTrue(json.contains("\"flightIata\""));
        assertTrue(json.contains("\"depIata\""));
        assertTrue(json.contains("\"arrIata\""));
        assertTrue(json.contains("\"depTime\""));
        assertTrue(json.contains("\"arrTime\""));
        assertTrue(json.contains("\"depEstimated\""));
        assertTrue(json.contains("\"arrEstimated\""));
        assertTrue(json.contains("\"status\""));
        assertTrue(json.contains("\"delayedMin\""));
    }

    @Test
    void nullableFieldsCanBeNull() throws Exception {
        FlightSchedule schedule = new FlightSchedule(
                "AA100", "BWI", "LAX",
                "2026-03-28T10:00:00+00:00", null,
                null, null,
                null, null
        );

        String json = mapper.writeValueAsString(schedule);
        FlightSchedule deserialized = mapper.readValue(json, FlightSchedule.class);

        assertEquals("AA100", deserialized.flightIata());
        assertEquals("BWI", deserialized.depIata());
        assertEquals("LAX", deserialized.arrIata());
        assertEquals("2026-03-28T10:00:00+00:00", deserialized.depTime());
        assertNull(deserialized.arrTime());
        assertNull(deserialized.depEstimated());
        assertNull(deserialized.arrEstimated());
        assertNull(deserialized.status());
        assertNull(deserialized.delayedMin());
    }

    @Test
    void recordEquality() {
        FlightSchedule a = new FlightSchedule("AA100", "BWI", "LAX",
                "2026-03-28T10:00:00+00:00", "2026-03-28T16:00:00+00:00",
                null, null, "scheduled", null);
        FlightSchedule b = new FlightSchedule("AA100", "BWI", "LAX",
                "2026-03-28T10:00:00+00:00", "2026-03-28T16:00:00+00:00",
                null, null, "scheduled", null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
