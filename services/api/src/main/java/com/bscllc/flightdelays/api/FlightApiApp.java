package com.bscllc.flightdelays.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.lettuce.core.KeyScanArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class FlightApiApp {

    private static final Logger log = LoggerFactory.getLogger(FlightApiApp.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    private static final int HTTP_PORT = Integer.parseInt(env("HTTP_PORT", "8084"));
    private static final int METRICS_PORT = Integer.parseInt(env("METRICS_PORT", "8085"));

    private static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private static RedisCommands<String, String> redis;

    public static void main(String[] args) {
        log.info("Flight API starting — http port {}, metrics port {}", HTTP_PORT, METRICS_PORT);

        // Redis
        RedisClient redisClient = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
        redis = redisClient.connect().sync();

        // Metrics server
        Javalin metricsApp = Javalin.create().start(METRICS_PORT);
        metricsApp.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(registry.scrape());
        });

        // API server
        Javalin app = Javalin.create(config -> {
            config.useVirtualThreads = true;
            config.showJavalinBanner = false;
        }).start(HTTP_PORT);

        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "*");
        });

        app.get("/health", ctx -> ctx.json(java.util.Map.of("status", "ok")));
        app.get("/flights", FlightApiApp::listFlights);
        app.get("/flights/{icao24}", FlightApiApp::getFlight);
        app.get("/delays", FlightApiApp::listDelays);

        // Request metrics
        app.after(ctx -> {
            String method = ctx.method().name();
            String path = ctx.matchedPath();
            int status = ctx.statusCode();
            Counter.builder("http.requests.total")
                .tag("method", method).tag("path", path).tag("status", String.valueOf(status))
                .register(registry).increment();
        });

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down...");
            app.stop();
            metricsApp.stop();
            redisClient.shutdown();
        }));
    }

    private static void listFlights(Context ctx) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        ArrayNode flights = mapper.createArrayNode();

        var scanArgs = KeyScanArgs.Builder.matches("flight:enriched:*").limit(1000);
        var iterator = ScanIterator.scan(redis, scanArgs);
        while (iterator.hasNext()) {
            String key = iterator.next();
            String val = redis.get(key);
            if (val != null) {
                flights.add(mapper.readTree(val));
            }
        }

        ctx.contentType("application/json");
        ctx.result(mapper.writeValueAsString(flights));
        sample.stop(Timer.builder("http.request.duration.seconds")
            .tag("method", "GET").tag("path", "/flights").register(registry));
    }

    private static void getFlight(Context ctx) throws Exception {
        String icao24 = ctx.pathParam("icao24");
        String val = redis.get("flight:enriched:" + icao24);
        if (val == null) {
            ctx.status(404).json(java.util.Map.of("error", "Flight not found", "icao24", icao24));
            return;
        }
        ctx.contentType("application/json");
        ctx.result(val);
    }

    private static void listDelays(Context ctx) throws Exception {
        ArrayNode delays = mapper.createArrayNode();

        var scanArgs = KeyScanArgs.Builder.matches("flight:delay:*").limit(1000);
        var iterator = ScanIterator.scan(redis, scanArgs);
        while (iterator.hasNext()) {
            String key = iterator.next();
            String val = redis.get(key);
            if (val != null) {
                delays.add(mapper.readTree(val));
            }
        }

        ctx.contentType("application/json");
        ctx.result(mapper.writeValueAsString(delays));
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }
}
