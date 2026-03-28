package com.bscllc.flightdelays.airlabs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AirLabsResponseParsingTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void parseScheduleResponse() throws Exception {
        String json = """
            {"response": [{"flight_iata": "AA100", "dep_iata": "BWI", "arr_iata": "LAX", "dep_time": "2026-03-28T10:00:00+00:00", "arr_time": "2026-03-28T16:00:00+00:00", "dep_estimated": null, "arr_estimated": null, "status": "scheduled", "delayed": null}]}
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode response = root.has("response") ? root.get("response") : root;

        assertTrue(response.isArray());
        assertEquals(1, response.size());

        JsonNode flight = response.get(0);
        assertEquals("AA100", textOrNull(flight, "flight_iata"));
        assertEquals("BWI", textOrNull(flight, "dep_iata"));
        assertEquals("LAX", textOrNull(flight, "arr_iata"));
        assertEquals("2026-03-28T10:00:00+00:00", textOrNull(flight, "dep_time"));
        assertEquals("2026-03-28T16:00:00+00:00", textOrNull(flight, "arr_time"));
        assertNull(textOrNull(flight, "dep_estimated"));
        assertNull(textOrNull(flight, "arr_estimated"));
        assertEquals("scheduled", textOrNull(flight, "status"));
    }

    @Test
    void parseDelayResponse() throws Exception {
        String json = """
            {"response": [{"flight_iata": "AA100", "delayed": 45}]}
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode response = root.has("response") ? root.get("response") : root;

        assertTrue(response.isArray());
        assertEquals(1, response.size());

        JsonNode delay = response.get(0);
        String flightIata = textOrNull(delay, "flight_iata");
        int delayedMin = delay.has("delayed") ? delay.get("delayed").asInt(0) : 0;

        assertEquals("AA100", flightIata);
        assertEquals(45, delayedMin);
    }

    @Test
    void responseWrappedInResponseKey() throws Exception {
        String json = """
            {"response": [{"flight_iata": "DL200", "delayed": 10}, {"flight_iata": "UA300", "delayed": 0}]}
            """;

        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("response"));

        JsonNode response = root.get("response");
        assertTrue(response.isArray());
        assertEquals(2, response.size());
    }

    @Test
    void responseAsBareArray() throws Exception {
        String json = """
            [{"flight_iata": "DL200", "delayed": 10}, {"flight_iata": "UA300", "delayed": 0}]
            """;

        JsonNode root = mapper.readTree(json);
        // Mimic the fallback logic from AirLabsPollerApp
        JsonNode response = root.has("response") ? root.get("response") : root;

        assertTrue(response.isArray());
        assertEquals(2, response.size());
        assertEquals("DL200", textOrNull(response.get(0), "flight_iata"));
        assertEquals("UA300", textOrNull(response.get(1), "flight_iata"));
    }

    @Test
    void detectErrorResponse() throws Exception {
        String json = """
            {"error": {"message": "Unauthorized", "code": "auth_failed"}}
            """;

        JsonNode root = mapper.readTree(json);

        assertTrue(root.has("error"));
        assertFalse(root.has("response"));

        JsonNode error = root.get("error");
        assertEquals("Unauthorized", error.get("message").asText());
        assertEquals("auth_failed", error.get("code").asText());
    }

    @Test
    void handleNullDelayedField() throws Exception {
        String json = """
            {"response": [{"flight_iata": "AA100", "dep_iata": "BWI", "arr_iata": "LAX", "dep_time": "2026-03-28T10:00:00+00:00", "delayed": null}]}
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode response = root.get("response");
        JsonNode flight = response.get(0);

        // Mimic the null-safe extraction from AirLabsPollerApp
        Integer delayedMin = flight.has("delayed") && !flight.get("delayed").isNull()
                ? flight.get("delayed").asInt() : null;

        assertNull(delayedMin);
    }

    @Test
    void handleMissingFieldsGracefully() throws Exception {
        // Minimal response with only flight_iata and dep_time — other fields missing entirely
        String json = """
            {"response": [{"flight_iata": "WN500", "dep_time": "2026-03-28T08:00:00+00:00"}]}
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode response = root.get("response");
        JsonNode flight = response.get(0);

        assertEquals("WN500", textOrNull(flight, "flight_iata"));
        assertEquals("2026-03-28T08:00:00+00:00", textOrNull(flight, "dep_time"));
        assertNull(textOrNull(flight, "dep_iata"));
        assertNull(textOrNull(flight, "arr_iata"));
        assertNull(textOrNull(flight, "arr_time"));
        assertNull(textOrNull(flight, "dep_estimated"));
        assertNull(textOrNull(flight, "arr_estimated"));
        assertNull(textOrNull(flight, "status"));

        Integer delayedMin = flight.has("delayed") && !flight.get("delayed").isNull()
                ? flight.get("delayed").asInt() : null;
        assertNull(delayedMin);
    }

    @Test
    void buildFlightScheduleFromParsedResponse() throws Exception {
        String json = """
            {"response": [{"flight_iata": "AA100", "dep_iata": "BWI", "arr_iata": "LAX", "dep_time": "2026-03-28T10:00:00+00:00", "arr_time": "2026-03-28T16:00:00+00:00", "dep_estimated": null, "arr_estimated": null, "status": "scheduled", "delayed": null}]}
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode response = root.get("response");
        JsonNode flight = response.get(0);

        String flightIata = textOrNull(flight, "flight_iata");
        String depIata = textOrNull(flight, "dep_iata");
        String arrIata = textOrNull(flight, "arr_iata");
        String depTime = textOrNull(flight, "dep_time");
        String arrTime = textOrNull(flight, "arr_time");
        String depEstimated = textOrNull(flight, "dep_estimated");
        String arrEstimated = textOrNull(flight, "arr_estimated");
        String status = textOrNull(flight, "status");
        Integer delayedMin = flight.has("delayed") && !flight.get("delayed").isNull()
                ? flight.get("delayed").asInt() : null;

        FlightSchedule schedule = new FlightSchedule(flightIata, depIata, arrIata,
                depTime, arrTime, depEstimated, arrEstimated, status, delayedMin);

        assertEquals("AA100", schedule.flightIata());
        assertEquals("BWI", schedule.depIata());
        assertEquals("LAX", schedule.arrIata());
        assertNull(schedule.delayedMin());

        // Verify it serializes to JSON and back
        String scheduleJson = mapper.writeValueAsString(schedule);
        FlightSchedule deserialized = mapper.readValue(scheduleJson, FlightSchedule.class);
        assertEquals(schedule, deserialized);
    }

    @Test
    void buildFlightDelayFromParsedResponse() throws Exception {
        String json = """
            {"response": [{"flight_iata": "AA100", "delayed": 45}]}
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode response = root.get("response");
        JsonNode delay = response.get(0);

        String flightIata = textOrNull(delay, "flight_iata");
        int delayedMin = delay.has("delayed") ? delay.get("delayed").asInt(0) : 0;

        FlightDelay fd = new FlightDelay(flightIata, delayedMin, Instant.now());

        assertEquals("AA100", fd.flightIata());
        assertEquals(45, fd.delayedMin());
        assertNotNull(fd.recordedAt());
    }

    @Test
    void parseMultipleSchedulesAndCountValid() throws Exception {
        String json = """
            {"response": [
                {"flight_iata": "AA100", "dep_iata": "BWI", "arr_iata": "LAX", "dep_time": "2026-03-28T10:00:00+00:00", "status": "scheduled", "delayed": null},
                {"dep_iata": "JFK", "arr_iata": "SFO", "dep_time": "2026-03-28T11:00:00+00:00"},
                {"flight_iata": "DL300", "dep_iata": "ATL", "arr_iata": "ORD", "dep_time": "2026-03-28T12:00:00+00:00", "status": "active", "delayed": 30},
                {"flight_iata": "UA400", "dep_iata": "DEN", "arr_iata": "SEA"}
            ]}
            """;

        JsonNode root = mapper.readTree(json);
        JsonNode response = root.get("response");

        List<FlightSchedule> schedules = new ArrayList<>();
        for (JsonNode flight : response) {
            String flightIata = textOrNull(flight, "flight_iata");
            if (flightIata == null) continue;

            String depTime = textOrNull(flight, "dep_time");
            if (depTime == null) continue;

            schedules.add(new FlightSchedule(
                    flightIata,
                    textOrNull(flight, "dep_iata"),
                    textOrNull(flight, "arr_iata"),
                    depTime,
                    textOrNull(flight, "arr_time"),
                    textOrNull(flight, "dep_estimated"),
                    textOrNull(flight, "arr_estimated"),
                    textOrNull(flight, "status"),
                    flight.has("delayed") && !flight.get("delayed").isNull()
                            ? flight.get("delayed").asInt() : null
            ));
        }

        // Entry 1: valid (AA100). Entry 2: no flight_iata => skip. Entry 3: valid (DL300). Entry 4: no dep_time => skip.
        assertEquals(2, schedules.size());
        assertEquals("AA100", schedules.get(0).flightIata());
        assertEquals("DL300", schedules.get(1).flightIata());
        assertEquals(30, schedules.get(1).delayedMin());
    }

    /** Mirrors the textOrNull helper in AirLabsPollerApp. */
    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }
}
