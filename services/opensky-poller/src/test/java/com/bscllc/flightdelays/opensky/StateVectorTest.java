package com.bscllc.flightdelays.opensky;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StateVectorTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void serializationRoundtrip() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        StateVector sv = new StateVector("ab1234", "AAL100", -73.78, 40.64, 10000.0, false, 250.0, 180.5, now);

        String json = mapper.writeValueAsString(sv);
        StateVector deserialized = mapper.readValue(json, StateVector.class);

        assertEquals(sv.icao24(), deserialized.icao24());
        assertEquals(sv.callsign(), deserialized.callsign());
        assertEquals(sv.longitude(), deserialized.longitude(), 0.001);
        assertEquals(sv.latitude(), deserialized.latitude(), 0.001);
        assertEquals(sv.altitude(), deserialized.altitude(), 0.001);
        assertEquals(sv.onGround(), deserialized.onGround());
        assertEquals(sv.velocity(), deserialized.velocity(), 0.001);
        assertEquals(sv.heading(), deserialized.heading(), 0.001);
        assertEquals(sv.recordedAt(), deserialized.recordedAt());
    }

    @Test
    void allFieldsPresentInJson() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        StateVector sv = new StateVector("ab1234", "AAL100", -73.78, 40.64, 10000.0, false, 250.0, 180.5, now);

        String json = mapper.writeValueAsString(sv);

        assertTrue(json.contains("\"icao24\""));
        assertTrue(json.contains("\"callsign\""));
        assertTrue(json.contains("\"longitude\""));
        assertTrue(json.contains("\"latitude\""));
        assertTrue(json.contains("\"altitude\""));
        assertTrue(json.contains("\"onGround\""));
        assertTrue(json.contains("\"velocity\""));
        assertTrue(json.contains("\"heading\""));
        assertTrue(json.contains("\"recordedAt\""));
    }

    @Test
    void emptyCallsign() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        StateVector sv = new StateVector("ab1234", "", -73.78, 40.64, 10000.0, false, 250.0, 180.5, now);

        String json = mapper.writeValueAsString(sv);
        StateVector deserialized = mapper.readValue(json, StateVector.class);

        assertEquals("", deserialized.callsign());
        assertEquals(sv, deserialized);
    }

    @Test
    void zeroAltitudeAndVelocity() throws Exception {
        Instant now = Instant.parse("2026-03-28T14:30:00Z");
        StateVector sv = new StateVector("cd5678", "SWA456", -118.41, 33.94, 0.0, true, 0.0, 0.0, now);

        String json = mapper.writeValueAsString(sv);
        StateVector deserialized = mapper.readValue(json, StateVector.class);

        assertEquals(0.0, deserialized.altitude(), 0.001);
        assertEquals(0.0, deserialized.velocity(), 0.001);
        assertEquals(0.0, deserialized.heading(), 0.001);
        assertTrue(deserialized.onGround());
        assertEquals(sv, deserialized);
    }

    @Test
    void recordEquality() {
        Instant now = Instant.now();
        StateVector a = new StateVector("ab1234", "AAL100", -73.78, 40.64, 10000.0, false, 250.0, 180.5, now);
        StateVector b = new StateVector("ab1234", "AAL100", -73.78, 40.64, 10000.0, false, 250.0, 180.5, now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
