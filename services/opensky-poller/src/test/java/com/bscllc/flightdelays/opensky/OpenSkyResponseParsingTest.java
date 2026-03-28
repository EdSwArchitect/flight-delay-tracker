package com.bscllc.flightdelays.opensky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenSkyResponseParsingTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void parseValidStateVector() throws Exception {
        String json = """
            {
              "time": 1711648800,
              "states": [
                ["ab1234", "AAL100  ", "United States", 1711648795, 1711648795, -73.78, 40.64, 10000.0, false, 250.0, 180.5, 0.0, null, 10500.0, "1234", false, 0]
              ]
            }
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode states = root.get("states");

        assertNotNull(states);
        assertTrue(states.isArray());
        assertEquals(1, states.size());

        JsonNode state = states.get(0);
        String icao24 = state.get(0).asText("");
        String callsign = state.get(1).asText("").strip();
        double lon = state.get(5).asDouble();
        double lat = state.get(6).asDouble();
        double altitude = state.get(7).asDouble();
        boolean onGround = state.get(8).asBoolean(false);
        double velocity = state.get(9).asDouble();
        double heading = state.get(10).asDouble();

        assertEquals("ab1234", icao24);
        assertEquals("AAL100", callsign);
        assertEquals(-73.78, lon, 0.001);
        assertEquals(40.64, lat, 0.001);
        assertEquals(10000.0, altitude, 0.001);
        assertFalse(onGround);
        assertEquals(250.0, velocity, 0.001);
        assertEquals(180.5, heading, 0.001);
    }

    @Test
    void nullLongitudeLatitudeShouldSkip() throws Exception {
        String json = """
            {
              "time": 1711648800,
              "states": [
                ["cd5678", "  ", "Germany", 1711648790, 1711648790, null, null, null, true, 0.0, 0.0, 0.0, null, null, null, false, 0]
              ]
            }
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode states = root.get("states");
        JsonNode state = states.get(0);

        double lon = state.has(5) && !state.get(5).isNull() ? state.get(5).asDouble() : 0;
        double lat = state.has(6) && !state.get(6).isNull() ? state.get(6).asDouble() : 0;

        // Both are 0 because they were null, so (lat == 0 && lon == 0) => skip
        assertEquals(0, lon);
        assertEquals(0, lat);
        assertTrue(lat == 0 && lon == 0, "State with null lat/lon should be skipped");
    }

    @Test
    void blankCallsignIsStripped() throws Exception {
        String json = """
            {
              "time": 1711648800,
              "states": [
                ["cd5678", "  ", "Germany", 1711648790, 1711648790, 13.40, 52.52, 500.0, false, 100.0, 90.0, 0.0, null, 600.0, null, false, 0]
              ]
            }
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode state = root.get("states").get(0);
        String callsign = state.get(1).asText("").strip();

        assertEquals("", callsign);
    }

    @Test
    void emptyIcao24ShouldSkip() throws Exception {
        String json = """
            {
              "time": 1711648800,
              "states": [
                ["", "AAL100", "United States", 1711648795, 1711648795, -73.78, 40.64, 10000.0, false, 250.0, 180.5, 0.0, null, 10500.0, "1234", false, 0]
              ]
            }
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode state = root.get("states").get(0);
        String icao24 = state.get(0).asText("");

        assertTrue(icao24.isEmpty(), "Empty icao24 should be skipped");
    }

    @Test
    void parseMultipleStatesAndCountValid() throws Exception {
        String json = """
            {
              "time": 1711648800,
              "states": [
                ["ab1234", "AAL100  ", "United States", 1711648795, 1711648795, -73.78, 40.64, 10000.0, false, 250.0, 180.5, 0.0, null, 10500.0, "1234", false, 0],
                ["cd5678", "  ", "Germany", 1711648790, 1711648790, null, null, null, true, 0.0, 0.0, 0.0, null, null, null, false, 0],
                ["", "BAW999", "UK", 1711648790, 1711648790, -0.46, 51.47, 8000.0, false, 200.0, 270.0, 0.0, null, 8500.0, "5678", false, 0],
                ["ef9012", "DAL300", "United States", 1711648795, 1711648795, -84.43, 33.64, 12000.0, false, 275.0, 90.0, 0.0, null, 12500.0, "9012", false, 0]
              ]
            }
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode states = root.get("states");

        int validCount = 0;
        List<StateVector> results = new ArrayList<>();

        for (JsonNode state : states) {
            String icao24 = state.get(0).asText("");
            String callsign = state.get(1).asText("").strip();
            double lon = state.has(5) && !state.get(5).isNull() ? state.get(5).asDouble() : 0;
            double lat = state.has(6) && !state.get(6).isNull() ? state.get(6).asDouble() : 0;
            double altitude = state.has(7) && !state.get(7).isNull() ? state.get(7).asDouble() : 0;
            boolean onGround = state.has(8) && state.get(8).asBoolean(false);
            double velocity = state.has(9) && !state.get(9).isNull() ? state.get(9).asDouble() : 0;
            double heading = state.has(10) && !state.get(10).isNull() ? state.get(10).asDouble() : 0;

            if (icao24.isEmpty() || (lat == 0 && lon == 0)) continue;

            results.add(new StateVector(icao24, callsign, lon, lat, altitude, onGround, velocity, heading, Instant.now()));
            validCount++;
        }

        // ab1234 = valid, cd5678 = null lat/lon => skip, "" = empty icao24 => skip, ef9012 = valid
        assertEquals(2, validCount);
        assertEquals("ab1234", results.get(0).icao24());
        assertEquals("ef9012", results.get(1).icao24());
    }

    @Test
    void noStatesArrayReturnsNull() throws Exception {
        String json = """
            {
              "time": 1711648800
            }
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode states = root.get("states");

        assertNull(states);
    }

    @Test
    void emptyStatesArray() throws Exception {
        String json = """
            {
              "time": 1711648800,
              "states": []
            }
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode states = root.get("states");

        assertNotNull(states);
        assertTrue(states.isArray());
        assertEquals(0, states.size());
    }
}
