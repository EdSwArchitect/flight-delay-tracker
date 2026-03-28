package com.bscllc.flightdelays.airlabs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FlightDelayTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void serializationRoundtrip() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        FlightDelay delay = new FlightDelay("AA100", 45, now);

        String json = mapper.writeValueAsString(delay);
        FlightDelay deserialized = mapper.readValue(json, FlightDelay.class);

        assertEquals(delay.flightIata(), deserialized.flightIata());
        assertEquals(delay.delayedMin(), deserialized.delayedMin());
        assertEquals(delay.recordedAt(), deserialized.recordedAt());
    }

    @Test
    void allFieldsPresentInJson() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        FlightDelay delay = new FlightDelay("AA100", 45, now);

        String json = mapper.writeValueAsString(delay);

        assertTrue(json.contains("\"flightIata\""));
        assertTrue(json.contains("\"delayedMin\""));
        assertTrue(json.contains("\"recordedAt\""));
    }

    @Test
    void zeroDelayMinutes() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        FlightDelay delay = new FlightDelay("DL200", 0, now);

        String json = mapper.writeValueAsString(delay);
        FlightDelay deserialized = mapper.readValue(json, FlightDelay.class);

        assertEquals(0, deserialized.delayedMin());
        assertEquals(delay, deserialized);
    }

    @Test
    void largeDelayMinutes() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        FlightDelay delay = new FlightDelay("UA500", 720, now);

        String json = mapper.writeValueAsString(delay);
        FlightDelay deserialized = mapper.readValue(json, FlightDelay.class);

        assertEquals(720, deserialized.delayedMin());
    }

    @Test
    void recordEquality() {
        Instant now = Instant.now();
        FlightDelay a = new FlightDelay("AA100", 45, now);
        FlightDelay b = new FlightDelay("AA100", 45, now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
