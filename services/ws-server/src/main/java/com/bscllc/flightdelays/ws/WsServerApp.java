package com.bscllc.flightdelays.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.lettuce.core.KeyScanArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WsServerApp {

    private static final Logger log = LoggerFactory.getLogger(WsServerApp.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    private static final int HTTP_PORT = Integer.parseInt(env("HTTP_PORT", "8086"));
    private static final int METRICS_PORT = Integer.parseInt(env("METRICS_PORT", "8087"));
    private static final int BROADCAST_INTERVAL = Integer.parseInt(env("BROADCAST_INTERVAL_SEC", "5"));

    private static final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();

    // Metrics
    private static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private static final Counter sessionsOpened = Counter.builder("ws.sessions.total").tag("event", "opened").register(registry);
    private static final Counter sessionsClosed = Counter.builder("ws.sessions.total").tag("event", "closed").register(registry);
    private static final Counter broadcastsSnapshot = Counter.builder("ws.broadcasts.total").tag("type", "snapshot").register(registry);
    private static final Counter broadcastsHeartbeat = Counter.builder("ws.broadcasts.total").tag("type", "heartbeat").register(registry);

    static {
        Gauge.builder("ws.sessions.active", sessions, Set::size).register(registry);
    }

    public static void main(String[] args) {
        log.info("WebSocket Server starting — http port {}, metrics port {}, broadcast interval {}s",
                HTTP_PORT, METRICS_PORT, BROADCAST_INTERVAL);

        // Redis
        RedisClient redisClient = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
        RedisCommands<String, String> redis = redisClient.connect().sync();

        // Metrics server
        Javalin metricsApp = Javalin.create().start(METRICS_PORT);
        metricsApp.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(registry.scrape());
        });

        // WebSocket server
        Javalin app = Javalin.create(config -> {
            config.useVirtualThreads = true;
            config.showJavalinBanner = false;
        }).start(HTTP_PORT);

        app.ws("/ws/positions", ws -> {
            ws.onConnect(ctx -> {
                sessions.add(ctx);
                sessionsOpened.increment();
                log.info("WebSocket connected — {} active sessions", sessions.size());

                // Send initial snapshot
                String snapshot = buildSnapshot(redis);
                ctx.send(snapshot);
            });

            ws.onClose(ctx -> {
                sessions.remove(ctx);
                sessionsClosed.increment();
                log.info("WebSocket disconnected — {} active sessions", sessions.size());
            });

            ws.onError(ctx -> {
                sessions.remove(ctx);
                log.warn("WebSocket error", ctx.error());
            });
        });

        app.get("/health", ctx -> ctx.json(java.util.Map.of("status", "ok")));

        // Broadcast loop
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        scheduler.scheduleAtFixedRate(() -> broadcast(redis), BROADCAST_INTERVAL, BROADCAST_INTERVAL, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down...");
            scheduler.shutdown();
            app.stop();
            metricsApp.stop();
            redisClient.shutdown();
        }));
    }

    private static void broadcast(RedisCommands<String, String> redis) {
        if (sessions.isEmpty()) return;

        try {
            String snapshot = buildSnapshot(redis);

            int sent = 0;
            for (WsContext ctx : sessions) {
                try {
                    ctx.send(snapshot);
                    sent++;
                } catch (Exception e) {
                    sessions.remove(ctx);
                }
            }

            broadcastsSnapshot.increment();
            log.debug("Broadcast snapshot to {} clients", sent);

        } catch (Exception e) {
            log.error("Broadcast failed", e);
        }
    }

    private static String buildSnapshot(RedisCommands<String, String> redis) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "snapshot");
        envelope.put("timestamp", Instant.now().toString());

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

        envelope.set("flights", flights);
        return mapper.writeValueAsString(envelope);
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }
}
