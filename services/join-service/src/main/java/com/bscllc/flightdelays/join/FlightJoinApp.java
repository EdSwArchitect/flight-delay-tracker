package com.bscllc.flightdelays.join;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.lettuce.core.KeyScanArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FlightJoinApp {

    private static final Logger log = LoggerFactory.getLogger(FlightJoinApp.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    private static final int METRICS_PORT = Integer.parseInt(env("METRICS_PORT", "8083"));
    private static final int JOIN_INTERVAL = Integer.parseInt(env("JOIN_INTERVAL_SEC", "10"));

    private static final CallsignResolver resolver = new CallsignResolver();

    // Metrics
    private static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private static final Counter joinsResolved = Counter.builder("flight.joins.total").tag("result", "resolved").register(registry);
    private static final Counter joinsPositionOnly = Counter.builder("flight.joins.total").tag("result", "position_only").register(registry);
    private static final Counter joinsUnresolvable = Counter.builder("flight.joins.total").tag("result", "unresolvable").register(registry);
    private static final Timer joinDuration = Timer.builder("flight.join.duration.seconds").register(registry);
    private static final AtomicInteger enrichedCount = new AtomicInteger(0);
    private static final Counter missBlankCallsign = Counter.builder("flight.index.miss.reasons").tag("reason", "blank_callsign").register(registry);
    private static final Counter missNoMatch = Counter.builder("flight.index.miss.reasons").tag("reason", "no_match").register(registry);
    private static final Counter missNormalisation = Counter.builder("flight.index.miss.reasons").tag("reason", "normalisation_miss").register(registry);

    static {
        Gauge.builder("flight.enriched.count", enrichedCount, AtomicInteger::get).register(registry);
    }

    public static void main(String[] args) {
        log.info("Join Service starting — interval {}s, metrics port {}", JOIN_INTERVAL, METRICS_PORT);

        Javalin metricsApp = Javalin.create().start(METRICS_PORT);
        metricsApp.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(registry.scrape());
        });

        RedisClient redisClient = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
        RedisCommands<String, String> redis = redisClient.connect().sync();
        log.info("Connected to Redis at {}:{}", REDIS_HOST, REDIS_PORT);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        scheduler.scheduleAtFixedRate(() -> joinCycle(redis), 5, JOIN_INTERVAL, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down...");
            scheduler.shutdown();
            redisClient.shutdown();
            metricsApp.stop();
        }));
    }

    private static void joinCycle(RedisCommands<String, String> redis) {
        Timer.Sample sample = Timer.start(registry);
        try {
            // Load all callsign index entries
            Map<String, String> callsignIndex = redis.hgetall("callsign:index");
            if (callsignIndex == null) callsignIndex = new HashMap<>();

            // Scan for all position keys
            int total = 0;
            var scanArgs = KeyScanArgs.Builder.matches("flight:position:*").limit(500);
            var iterator = ScanIterator.scan(redis, scanArgs);

            while (iterator.hasNext()) {
                String key = iterator.next();
                try {
                    String posJson = redis.get(key);
                    if (posJson == null) continue;

                    JsonNode posNode = mapper.readTree(posJson);
                    String icao24 = posNode.get("icao24").asText();
                    String callsign = posNode.has("callsign") ? posNode.get("callsign").asText("").strip() : "";

                    String enrichedJson = resolve(callsign, icao24, posJson, callsignIndex, redis);
                    redis.set("flight:enriched:" + icao24, enrichedJson, SetArgs.Builder.ex(70));
                    total++;
                } catch (Exception e) {
                    log.warn("Failed to process {}", key, e);
                }
            }

            enrichedCount.set(total);
            log.info("Join cycle complete — {} enriched records", total);

        } catch (Exception e) {
            log.error("Join cycle failed", e);
        } finally {
            sample.stop(joinDuration);
        }
    }

    private static String resolve(String callsign, String icao24, String posJson,
                                    Map<String, String> callsignIndex,
                                    RedisCommands<String, String> redis) throws Exception {
        Instant now = Instant.now();
        CallsignResolver.Resolution resolution = resolver.resolve(callsign, callsignIndex);

        switch (resolution.result()) {
            case UNRESOLVABLE -> {
                missBlankCallsign.increment();
                joinsUnresolvable.increment();
                return mapper.writeValueAsString(Map.of(
                    "type", "unresolvable",
                    "icao24", icao24,
                    "position", mapper.readTree(posJson),
                    "reason", resolution.reason(),
                    "resolvedAt", now.toString()
                ));
            }
            case POSITION_ONLY -> {
                if ("normalisation_miss".equals(resolution.reason())) {
                    missNormalisation.increment();
                } else {
                    missNoMatch.increment();
                }
                joinsPositionOnly.increment();
                return mapper.writeValueAsString(Map.of(
                    "type", "position_only",
                    "icao24", icao24,
                    "position", mapper.readTree(posJson),
                    "callsign", callsign,
                    "resolvedAt", now.toString()
                ));
            }
            case RESOLVED -> {
                String scheduleJson = callsignIndex.get(resolution.matchedKey());
                JsonNode schedule = mapper.readTree(scheduleJson);
                String flightIata = schedule.get("flightIata").asText();

                String delayJson = redis.get("flight:delay:" + flightIata);
                JsonNode delay = delayJson != null ? mapper.readTree(delayJson) : null;

                Map<String, Object> result = new HashMap<>();
                result.put("type", "resolved");
                result.put("icao24", icao24);
                result.put("position", mapper.readTree(posJson));
                result.put("schedule", schedule);
                result.put("delay", delay);
                result.put("resolvedAt", now.toString());

                joinsResolved.increment();
                return mapper.writeValueAsString(result);
            }
            default -> throw new IllegalStateException("Unknown resolution: " + resolution.result());
        }
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }
}
