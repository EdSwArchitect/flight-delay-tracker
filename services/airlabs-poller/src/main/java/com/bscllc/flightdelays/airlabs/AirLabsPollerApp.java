package com.bscllc.flightdelays.airlabs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AirLabsPollerApp {

    private static final Logger log = LoggerFactory.getLogger(AirLabsPollerApp.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    private static final int METRICS_PORT = Integer.parseInt(env("METRICS_PORT", "8082"));
    private static final int SCHEDULE_POLL_INTERVAL = Integer.parseInt(env("SCHEDULE_POLL_INTERVAL_SEC", "600"));
    private static final int DELAY_POLL_INTERVAL = Integer.parseInt(env("DELAY_POLL_INTERVAL_SEC", "120"));

    private static final String POSTGRES_HOST = env("POSTGRES_HOST", "localhost");
    private static final int POSTGRES_PORT = Integer.parseInt(env("POSTGRES_PORT", "5432"));
    private static final String POSTGRES_DB = env("POSTGRES_DB", "flightdb");
    private static final String POSTGRES_USER = env("POSTGRES_USER", "flightuser");
    private static final String POSTGRES_PASSWORD = env("POSTGRES_PASSWORD", "flightpass");

    private static final String AIRLABS_API_KEY = env("AIRLABS_API_KEY", "");
    private static final String AIRLABS_BASE_URL = "https://airlabs.co/api/v9";
    private static final String DEP_AIRPORT = env("DEP_AIRPORT", "BWI");

    // Metrics
    private static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private static final Counter pollsScheduleSuccess = Counter.builder("airlabs.polls.total").tag("endpoint", "schedules").tag("status", "success").register(registry);
    private static final Counter pollsScheduleFailure = Counter.builder("airlabs.polls.total").tag("endpoint", "schedules").tag("status", "failure").register(registry);
    private static final Counter pollsDelaySuccess = Counter.builder("airlabs.polls.total").tag("endpoint", "delays").tag("status", "success").register(registry);
    private static final Counter pollsDelayFailure = Counter.builder("airlabs.polls.total").tag("endpoint", "delays").tag("status", "failure").register(registry);
    private static final AtomicInteger delaysCount = new AtomicInteger(0);
    private static final AtomicInteger schedulesCount = new AtomicInteger(0);
    private static final AtomicInteger indexSize = new AtomicInteger(0);
    private static final Counter parseErrors = Counter.builder("airlabs.parse.errors").register(registry);

    static {
        Gauge.builder("airlabs.delays.count", delaysCount, AtomicInteger::get).register(registry);
        Gauge.builder("airlabs.schedules.count", schedulesCount, AtomicInteger::get).register(registry);
        Gauge.builder("airlabs.index.size", indexSize, AtomicInteger::get).register(registry);
    }

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) {
        log.info("AirLabs Poller starting — schedule poll {}s, delay poll {}s, metrics port {}",
                SCHEDULE_POLL_INTERVAL, DELAY_POLL_INTERVAL, METRICS_PORT);

        // Metrics server — always starts regardless of API key validity
        Javalin metricsApp = Javalin.create().start(METRICS_PORT);
        metricsApp.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(registry.scrape());
        });

        if (AIRLABS_API_KEY.isEmpty()) {
            log.warn("AIRLABS_API_KEY is not set — polling disabled, metrics server still running on port {}", METRICS_PORT);
            // Keep the process alive for liveness probes
            Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
                log.info("Shutting down...");
                metricsApp.stop();
            }));
            return;
        }

        // Redis
        RedisClient redisClient = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
        RedisCommands<String, String> redis = redisClient.connect().sync();
        log.info("Connected to Redis at {}:{}", REDIS_HOST, REDIS_PORT);

        // Postgres
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:postgresql://" + POSTGRES_HOST + ":" + POSTGRES_PORT + "/" + POSTGRES_DB);
        hikariConfig.setUsername(POSTGRES_USER);
        hikariConfig.setPassword(POSTGRES_PASSWORD);
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setMinimumIdle(1);
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        log.info("Connected to Postgres at {}:{}/{}", POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB);

        // Schedulers
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());
        scheduler.scheduleAtFixedRate(() -> pollSchedules(redis, dataSource), 0, SCHEDULE_POLL_INTERVAL, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> pollDelays(redis, dataSource), 5, DELAY_POLL_INTERVAL, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down...");
            scheduler.shutdown();
            redisClient.shutdown();
            dataSource.close();
            metricsApp.stop();
        }));
    }

    private static void pollSchedules(RedisCommands<String, String> redis, HikariDataSource ds) {
        try {
            String url = AIRLABS_BASE_URL + "/schedules?dep_iata=" + DEP_AIRPORT + "&api_key=" + AIRLABS_API_KEY;
            String body = fetch(url);
            if (body == null) { pollsScheduleFailure.increment(); return; }

            // Archive raw response
            archiveResponse(ds, "airlabs", "schedules", body);

            JsonNode root = mapper.readTree(body);
            JsonNode response = root.has("response") ? root.get("response") : root;
            if (!response.isArray()) { pollsScheduleFailure.increment(); return; }

            int count = 0;
            int indexed = 0;
            for (JsonNode flight : response) {
                try {
                    String flightIata = textOrNull(flight, "flight_iata");
                    if (flightIata == null) continue;

                    String depIata = textOrNull(flight, "dep_iata");
                    String arrIata = textOrNull(flight, "arr_iata");
                    String depTime = textOrNull(flight, "dep_time");
                    String arrTime = textOrNull(flight, "arr_time");
                    String depEstimated = textOrNull(flight, "dep_estimated");
                    String arrEstimated = textOrNull(flight, "arr_estimated");
                    String status = textOrNull(flight, "status");
                    Integer delayedMin = flight.has("delayed") && !flight.get("delayed").isNull()
                            ? flight.get("delayed").asInt() : null;

                    if (depTime == null || parseTimestamp(depTime) == null) continue;

                    // Upsert to Postgres
                    upsertSchedule(ds, flightIata, depIata, arrIata, depTime, arrTime,
                            depEstimated, arrEstimated, status, delayedMin);
                    count++;

                    // Build callsign index entry — flight_iata is typically the callsign
                    FlightSchedule schedule = new FlightSchedule(flightIata, depIata, arrIata,
                            depTime, arrTime, depEstimated, arrEstimated, status, delayedMin);
                    String scheduleJson = mapper.writeValueAsString(schedule);

                    // Index by flight_iata (e.g., "AA100") as the callsign
                    redis.hset("callsign:index", flightIata.replaceAll("\\s+", ""), scheduleJson);
                    indexed++;

                } catch (Exception e) {
                    log.warn("Failed to process schedule entry", e);
                    parseErrors.increment();
                }
            }

            redis.expire("callsign:index", 600);
            schedulesCount.set(count);
            indexSize.set(indexed);
            pollsScheduleSuccess.increment();
            log.info("Polled {} schedules, indexed {} callsigns", count, indexed);

        } catch (Exception e) {
            log.error("Schedule poll failed", e);
            pollsScheduleFailure.increment();
        }
    }

    private static void pollDelays(RedisCommands<String, String> redis, HikariDataSource ds) {
        try {
            String url = AIRLABS_BASE_URL + "/delays?type=departures&dep_iata=" + DEP_AIRPORT + "&api_key=" + AIRLABS_API_KEY;
            String body = fetch(url);
            if (body == null) { pollsDelayFailure.increment(); return; }

            archiveResponse(ds, "airlabs", "delays", body);

            JsonNode root = mapper.readTree(body);
            JsonNode response = root.has("response") ? root.get("response") : root;
            if (!response.isArray()) { pollsDelayFailure.increment(); return; }

            int count = 0;
            for (JsonNode delay : response) {
                try {
                    String flightIata = textOrNull(delay, "flight_iata");
                    if (flightIata == null) continue;

                    int delayedMin = delay.has("delayed") ? delay.get("delayed").asInt(0) : 0;

                    FlightDelay fd = new FlightDelay(flightIata, delayedMin, Instant.now());
                    String json = mapper.writeValueAsString(fd);
                    redis.set("flight:delay:" + flightIata, json, SetArgs.Builder.ex(300));
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to process delay entry", e);
                    parseErrors.increment();
                }
            }

            delaysCount.set(count);
            pollsDelaySuccess.increment();
            log.info("Polled {} delays", count);

        } catch (Exception e) {
            log.error("Delay poll failed", e);
            pollsDelayFailure.increment();
        }
    }

    private static String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("AirLabs API returned status {} for {}", resp.statusCode(), url);
            return null;
        }
        // AirLabs returns 200 with {"error":{...}} for invalid API keys
        String body = resp.body();
        if (body.contains("\"error\"")) {
            try {
                JsonNode node = mapper.readTree(body);
                if (node.has("error")) {
                    String msg = node.get("error").has("message")
                            ? node.get("error").get("message").asText() : body;
                    log.warn("AirLabs API error: {}", msg);
                    return null;
                }
            } catch (Exception ignored) {}
        }
        return body;
    }

    private static void upsertSchedule(HikariDataSource ds, String flightIata, String depIata,
                                         String arrIata, String depTime, String arrTime,
                                         String depEstimated, String arrEstimated,
                                         String status, Integer delayedMin) {
        String sql = """
            INSERT INTO flight_schedules (flight_iata, dep_iata, arr_iata, dep_time, arr_time,
                dep_estimated, arr_estimated, status, delayed_min)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (flight_iata, dep_time) DO UPDATE SET
                arr_time = EXCLUDED.arr_time,
                dep_estimated = EXCLUDED.dep_estimated,
                arr_estimated = EXCLUDED.arr_estimated,
                status = EXCLUDED.status,
                delayed_min = EXCLUDED.delayed_min,
                ingested_at = now()
            """;
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flightIata);
            ps.setString(2, depIata);
            ps.setString(3, arrIata);
            ps.setTimestamp(4, parseTimestamp(depTime));
            ps.setTimestamp(5, parseTimestamp(arrTime));
            ps.setTimestamp(6, parseTimestamp(depEstimated));
            ps.setTimestamp(7, parseTimestamp(arrEstimated));
            ps.setString(8, status);
            if (delayedMin != null) ps.setInt(9, delayedMin);
            else ps.setNull(9, java.sql.Types.INTEGER);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Upsert failed for {}: {}", flightIata, e.getMessage());
        }
    }

    private static void archiveResponse(HikariDataSource ds, String source, String endpoint, String payload) {
        String sql = "INSERT INTO raw_api_responses (source, endpoint, payload) VALUES (?, ?, ?::jsonb)";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, endpoint);
            ps.setString(3, payload);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Archive failed for {}/{}: {}", source, endpoint, e.getMessage());
        }
    }

    private static Timestamp parseTimestamp(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Timestamp.from(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Timestamp.valueOf(value);
            } catch (Exception e2) {
                // AirLabs returns "yyyy-MM-dd HH:mm" (no seconds) — parse as LocalDateTime
                try {
                    return Timestamp.valueOf(LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }
}
