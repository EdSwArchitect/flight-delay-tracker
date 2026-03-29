package com.bscllc.flightdelays.opensky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class OpenSkyPollerApp {

    private static final Logger log = LoggerFactory.getLogger(OpenSkyPollerApp.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // Config from environment
    private static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    private static final int METRICS_PORT = Integer.parseInt(env("METRICS_PORT", "8081"));
    private static final int POLL_INTERVAL = Integer.parseInt(env("OPENSKY_POLL_INTERVAL_SEC", "15"));

    // OpenSky OAuth2
    private static final String OPENSKY_TOKEN_URL = "https://opensky-network.org/api/oauth/token";
    private static final String OPENSKY_API_URL = "https://opensky-network.org/api/states/all";
    private static final String CLIENT_ID = env("OPENSKY_CLIENT_ID", "");
    private static final String CLIENT_SECRET = env("OPENSKY_CLIENT_SECRET", "");

    private static volatile String accessToken = null;
    private static volatile Instant tokenExpiry = Instant.EPOCH;

    // Metrics
    private static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private static final Counter pollsSuccess = Counter.builder("opensky.polls.total").tag("status", "success").register(registry);
    private static final Counter pollsFailure = Counter.builder("opensky.polls.total").tag("status", "failure").register(registry);
    private static final Timer pollDuration = Timer.builder("opensky.poll.duration.seconds").register(registry);
    private static final AtomicInteger stateVectorCount = new AtomicInteger(0);
    private static final Counter tokenRefreshSuccess = Counter.builder("opensky.token.refresh.total").tag("status", "success").register(registry);
    private static final Counter tokenRefreshFailure = Counter.builder("opensky.token.refresh.total").tag("status", "failure").register(registry);

    static {
        Gauge.builder("opensky.state_vectors.count", stateVectorCount, AtomicInteger::get).register(registry);
    }

    private static final long MAX_BACKOFF_SEC = 300; // 5 minute cap
    private static final AtomicLong currentDelaySec = new AtomicLong(POLL_INTERVAL);
    private static ScheduledExecutorService scheduler;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) {
        log.info("OpenSky Poller starting — poll interval {}s, metrics on port {}", POLL_INTERVAL, METRICS_PORT);

        // Metrics server
        Javalin metricsApp = Javalin.create().start(METRICS_PORT);
        metricsApp.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(registry.scrape());
        });

        // Redis
        RedisClient redisClient = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
        RedisCommands<String, String> redis = redisClient.connect().sync();
        log.info("Connected to Redis at {}:{}", REDIS_HOST, REDIS_PORT);

        // Scheduler with virtual threads — single-shot scheduling for backoff support
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        scheduleNext(redis, 0);

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down...");
            scheduler.shutdown();
            redisClient.shutdown();
            metricsApp.stop();
        }));
    }

    private static void scheduleNext(RedisCommands<String, String> redis, long delaySec) {
        scheduler.schedule(() -> {
            poll(redis);
            scheduleNext(redis, currentDelaySec.get());
        }, delaySec, TimeUnit.SECONDS);
    }

    private static void backoff() {
        long current = currentDelaySec.get();
        long next = Math.min(current * 2, MAX_BACKOFF_SEC);
        currentDelaySec.set(next);
        log.warn("Backing off — next poll in {}s", next);
    }

    private static void resetBackoff() {
        currentDelaySec.set(POLL_INTERVAL);
    }

    private static void poll(RedisCommands<String, String> redis) {
        Timer.Sample sample = Timer.start(registry);
        try {
            ensureToken();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(OPENSKY_API_URL))
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            if (accessToken != null) {
                reqBuilder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("OpenSky API returned 429 (rate limited)");
                pollsFailure.increment();
                backoff();
                return;
            }

            if (response.statusCode() != 200) {
                log.warn("OpenSky API returned status {}", response.statusCode());
                pollsFailure.increment();
                backoff();
                return;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode states = root.get("states");

            if (states == null || !states.isArray()) {
                log.warn("No states array in response");
                pollsFailure.increment();
                return;
            }

            int count = 0;
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

                StateVector sv = new StateVector(icao24, callsign, lon, lat, altitude, onGround, velocity, heading, Instant.now());
                String json = mapper.writeValueAsString(sv);
                redis.set("flight:position:" + icao24, json, SetArgs.Builder.ex(60));
                count++;
            }

            stateVectorCount.set(count);
            pollsSuccess.increment();
            resetBackoff();
            log.info("Polled {} state vectors from OpenSky", count);

        } catch (Exception e) {
            log.error("Poll failed", e);
            pollsFailure.increment();
            backoff();
        } finally {
            sample.stop(pollDuration);
        }
    }

    private static void ensureToken() {
        if (CLIENT_ID.isEmpty() || CLIENT_SECRET.isEmpty()) {
            log.debug("No OpenSky credentials — polling without auth");
            return;
        }
        if (accessToken != null && Instant.now().isBefore(tokenExpiry.minus(Duration.ofSeconds(30)))) {
            return; // token still valid
        }
        try {
            String body = "grant_type=client_credentials"
                    + "&client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OPENSKY_TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode token = mapper.readTree(resp.body());
                accessToken = token.get("access_token").asText();
                int expiresIn = token.get("expires_in").asInt(3600);
                tokenExpiry = Instant.now().plusSeconds(expiresIn);
                tokenRefreshSuccess.increment();
                log.info("OAuth2 token refreshed, expires in {}s", expiresIn);
            } else {
                log.warn("Token refresh failed: status {}", resp.statusCode());
                tokenRefreshFailure.increment();
            }
        } catch (Exception e) {
            log.error("Token refresh error", e);
            tokenRefreshFailure.increment();
        }
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }
}
