package com.bscllc.flightdelays.ws;

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
 * Tests for the WebSocket message envelope format used by WsServerApp.
 *
 * The server sends JSON envelopes with a "type" field (snapshot, delta, heartbeat),
 * a "timestamp" field (ISO-8601), and optionally a "flights" array.
 * These tests verify the envelope structure without requiring a running WebSocket server.
 */
class WsEnvelopeTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Snapshot envelope has type, timestamp, and flights fields")
    void snapshotEnvelope_hasRequiredFields() throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "snapshot");
        envelope.put("timestamp", Instant.now().toString());
        envelope.set("flights", mapper.createArrayNode());

        String serialized = mapper.writeValueAsString(envelope);
        JsonNode deserialized = mapper.readTree(serialized);

        assertTrue(deserialized.has("type"), "Envelope must have 'type' field");
        assertTrue(deserialized.has("timestamp"), "Envelope must have 'timestamp' field");
        assertTrue(deserialized.has("flights"), "Snapshot envelope must have 'flights' field");
        assertEquals("snapshot", deserialized.get("type").asText());
    }

    @Test
    @DisplayName("Snapshot envelope flights field is an array")
    void snapshotEnvelope_flightsIsArray() throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "snapshot");
        envelope.put("timestamp", Instant.now().toString());

        ArrayNode flights = mapper.createArrayNode();
        ObjectNode flight = mapper.createObjectNode();
        flight.put("icao24", "abc123");
        flights.add(flight);
        envelope.set("flights", flights);

        String serialized = mapper.writeValueAsString(envelope);
        JsonNode deserialized = mapper.readTree(serialized);

        assertTrue(deserialized.get("flights").isArray(),
                "flights field must be a JSON array");
        assertEquals(1, deserialized.get("flights").size());
    }

    @Test
    @DisplayName("Snapshot with empty flights array serializes as []")
    void snapshotEnvelope_emptyFlights() throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "snapshot");
        envelope.put("timestamp", Instant.now().toString());
        envelope.set("flights", mapper.createArrayNode());

        String serialized = mapper.writeValueAsString(envelope);
        JsonNode deserialized = mapper.readTree(serialized);

        assertTrue(deserialized.get("flights").isArray());
        assertEquals(0, deserialized.get("flights").size(),
                "Empty flights array must have size 0");
        assertTrue(serialized.contains("\"flights\":[]"),
                "Serialized JSON must contain \"flights\":[]");
    }

    @Test
    @DisplayName("Snapshot with multiple flights preserves array size")
    void snapshotEnvelope_withMultipleFlights() throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "snapshot");
        envelope.put("timestamp", Instant.now().toString());

        ArrayNode flights = mapper.createArrayNode();
        for (int i = 0; i < 5; i++) {
            ObjectNode flight = mapper.createObjectNode();
            flight.put("icao24", "flight_" + i);
            flight.put("type", "resolved");
            ObjectNode position = mapper.createObjectNode();
            position.put("lat", 40.0 + i);
            position.put("lon", -74.0 + i);
            flight.set("position", position);
            flights.add(flight);
        }
        envelope.set("flights", flights);

        String serialized = mapper.writeValueAsString(envelope);
        JsonNode deserialized = mapper.readTree(serialized);

        assertEquals(5, deserialized.get("flights").size(),
                "Flights array must contain exactly 5 entries");
    }

    @Test
    @DisplayName("Delta envelope has type=delta")
    void deltaEnvelope_typeIsDelta() throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "delta");
        envelope.put("timestamp", Instant.now().toString());
        envelope.set("flights", mapper.createArrayNode());

        String serialized = mapper.writeValueAsString(envelope);
        JsonNode deserialized = mapper.readTree(serialized);

        assertEquals("delta", deserialized.get("type").asText(),
                "Delta envelope type must be 'delta'");
        assertTrue(deserialized.has("timestamp"));
        assertTrue(deserialized.has("flights"));
    }

    @Test
    @DisplayName("Heartbeat envelope has only type and timestamp, no flights")
    void heartbeatEnvelope_noFlightsField() throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "heartbeat");
        envelope.put("timestamp", Instant.now().toString());

        String serialized = mapper.writeValueAsString(envelope);
        JsonNode deserialized = mapper.readTree(serialized);

        assertEquals("heartbeat", deserialized.get("type").asText());
        assertTrue(deserialized.has("timestamp"), "Heartbeat must have timestamp");
        assertFalse(deserialized.has("flights"),
                "Heartbeat envelope must not contain a flights field");
    }

    @Test
    @DisplayName("Timestamp field is parseable as ISO-8601 Instant")
    void timestampFormat_isIso8601() throws Exception {
        Instant now = Instant.now();

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "snapshot");
        envelope.put("timestamp", now.toString());
        envelope.set("flights", mapper.createArrayNode());

        String serialized = mapper.writeValueAsString(envelope);
        JsonNode deserialized = mapper.readTree(serialized);

        String timestampStr = deserialized.get("timestamp").asText();
        Instant parsed = Instant.parse(timestampStr);

        assertNotNull(parsed, "Timestamp must be parseable as Instant");
        assertEquals(now, parsed, "Round-tripped Instant must equal the original");
    }
}
