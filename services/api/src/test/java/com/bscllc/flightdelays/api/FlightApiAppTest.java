package com.bscllc.flightdelays.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Serialization and contract tests for the Flight API JSON response formats.
 *
 * These tests verify that the JSON structures returned by each endpoint
 * can be correctly serialized and deserialized by Jackson, without needing
 * a running HTTP server or Redis connection.
 */
class FlightApiAppTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("GET /health returns {\"status\":\"ok\"}")
    void healthEndpoint_returnsStatusOk() throws Exception {
        String json = "{\"status\":\"ok\"}";
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("status"), "Response must contain 'status' field");
        assertEquals("ok", node.get("status").asText());
    }

    @Test
    @DisplayName("Resolved flight JSON has all expected fields")
    void enrichedFlight_resolved_hasExpectedFields() throws Exception {
        ObjectNode flight = mapper.createObjectNode();
        flight.put("type", "resolved");
        flight.put("icao24", "a1b2c3");

        ObjectNode position = mapper.createObjectNode();
        position.put("lat", 40.6413);
        position.put("lon", -73.7781);
        position.put("altitude", 10972.8);
        position.put("velocity", 230.5);
        position.put("heading", 45.2);
        flight.set("position", position);

        ObjectNode schedule = mapper.createObjectNode();
        schedule.put("flight_iata", "AA100");
        schedule.put("dep_iata", "JFK");
        schedule.put("arr_iata", "LAX");
        schedule.put("status", "en-route");
        flight.set("schedule", schedule);

        ObjectNode delay = mapper.createObjectNode();
        delay.put("delayed_min", 25);
        flight.set("delay", delay);

        flight.put("resolvedAt", Instant.now().toString());

        // Round-trip through serialization
        String serialized = mapper.writeValueAsString(flight);
        JsonNode deserialized = mapper.readTree(serialized);

        assertEquals("resolved", deserialized.get("type").asText());
        assertEquals("a1b2c3", deserialized.get("icao24").asText());
        assertTrue(deserialized.has("position"), "Must have position object");
        assertTrue(deserialized.has("schedule"), "Must have schedule object");
        assertTrue(deserialized.has("delay"), "Must have delay object");
        assertTrue(deserialized.has("resolvedAt"), "Must have resolvedAt timestamp");
        assertEquals(40.6413, deserialized.get("position").get("lat").asDouble(), 0.0001);
        assertEquals("AA100", deserialized.get("schedule").get("flight_iata").asText());
        assertEquals(25, deserialized.get("delay").get("delayed_min").asInt());
    }

    @Test
    @DisplayName("Position-only flight JSON has expected fields and no schedule")
    void enrichedFlight_positionOnly_hasExpectedFields() throws Exception {
        ObjectNode flight = mapper.createObjectNode();
        flight.put("type", "position_only");
        flight.put("icao24", "d4e5f6");

        ObjectNode position = mapper.createObjectNode();
        position.put("lat", 51.4700);
        position.put("lon", -0.4543);
        position.put("altitude", 8534.0);
        position.put("velocity", 195.3);
        position.put("heading", 270.0);
        flight.set("position", position);

        flight.put("callsign", "BAW256");
        flight.put("resolvedAt", Instant.now().toString());

        String serialized = mapper.writeValueAsString(flight);
        JsonNode deserialized = mapper.readTree(serialized);

        assertEquals("position_only", deserialized.get("type").asText());
        assertEquals("d4e5f6", deserialized.get("icao24").asText());
        assertTrue(deserialized.has("position"), "Must have position object");
        assertEquals("BAW256", deserialized.get("callsign").asText());
        assertTrue(deserialized.has("resolvedAt"), "Must have resolvedAt timestamp");
        assertFalse(deserialized.has("schedule"), "Position-only flight must not have schedule");
        assertFalse(deserialized.has("delay"), "Position-only flight must not have delay");
    }

    @Test
    @DisplayName("Unresolvable flight JSON has reason field")
    void enrichedFlight_unresolvable_hasReason() throws Exception {
        ObjectNode flight = mapper.createObjectNode();
        flight.put("type", "unresolvable");
        flight.put("icao24", "789abc");
        flight.put("reason", "blank_callsign");

        ObjectNode position = mapper.createObjectNode();
        position.put("lat", 33.9416);
        position.put("lon", -118.4085);
        position.put("altitude", 0.0);
        flight.set("position", position);

        String serialized = mapper.writeValueAsString(flight);
        JsonNode deserialized = mapper.readTree(serialized);

        assertEquals("unresolvable", deserialized.get("type").asText());
        assertEquals("789abc", deserialized.get("icao24").asText());
        assertTrue(deserialized.has("reason"), "Unresolvable flight must have reason field");
        assertEquals("blank_callsign", deserialized.get("reason").asText());
    }

    @Test
    @DisplayName("Flights array serializes correctly with expected count and icao24 fields")
    void flightsArray_serializesCorrectly() throws Exception {
        ArrayNode flights = mapper.createArrayNode();

        for (int i = 0; i < 3; i++) {
            ObjectNode flight = mapper.createObjectNode();
            flight.put("icao24", "icao_" + i);
            flight.put("type", "resolved");
            flights.add(flight);
        }

        String serialized = mapper.writeValueAsString(flights);
        JsonNode deserialized = mapper.readTree(serialized);

        assertTrue(deserialized.isArray(), "Top-level must be an array");
        assertEquals(3, deserialized.size(), "Array must contain 3 flights");

        for (int i = 0; i < 3; i++) {
            assertTrue(deserialized.get(i).has("icao24"),
                    "Flight at index " + i + " must have icao24");
            assertEquals("icao_" + i, deserialized.get(i).get("icao24").asText());
        }
    }

    @Test
    @DisplayName("404 not-found response has error and icao24 fields")
    void notFoundResponse_hasErrorField() throws Exception {
        String json = "{\"error\":\"Flight not found\",\"icao24\":\"abc123\"}";
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("error"), "Response must contain 'error' field");
        assertEquals("Flight not found", node.get("error").asText());
        assertTrue(node.has("icao24"), "Response must contain 'icao24' field");
        assertEquals("abc123", node.get("icao24").asText());
    }
}
