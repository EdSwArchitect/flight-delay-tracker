package com.bscllc.flightdelays.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ConcurrentHashMap.newKeySet() session tracking pattern
 * used in WsServerApp to manage active WebSocket sessions.
 *
 * Since WsContext cannot be easily instantiated in isolation, these tests
 * use String identifiers as stand-ins to verify the concurrent set behavior
 * that the session tracker relies on.
 */
class SessionTrackingTest {

    private Set<String> sessions;

    @BeforeEach
    void setUp() {
        sessions = ConcurrentHashMap.newKeySet();
    }

    @Test
    @DisplayName("New session set is empty")
    void newSessionSet_isEmpty() {
        assertTrue(sessions.isEmpty(), "Newly created session set must be empty");
        assertEquals(0, sessions.size());
    }

    @Test
    @DisplayName("Adding a session increases set size")
    void addSession_increasesSize() {
        sessions.add("session-1");

        assertEquals(1, sessions.size(), "Set size must be 1 after adding one session");
        assertTrue(sessions.contains("session-1"));
    }

    @Test
    @DisplayName("Removing a session decreases set size")
    void removeSession_decreasesSize() {
        sessions.add("session-1");
        sessions.add("session-2");
        assertEquals(2, sessions.size());

        sessions.remove("session-1");

        assertEquals(1, sessions.size(), "Set size must be 1 after removing one of two sessions");
        assertFalse(sessions.contains("session-1"), "Removed session must not be present");
        assertTrue(sessions.contains("session-2"), "Remaining session must still be present");
    }

    @Test
    @DisplayName("Duplicate add does not increase set size")
    void duplicateAdd_doesNotIncreaseSize() {
        sessions.add("session-1");
        assertEquals(1, sessions.size());

        boolean added = sessions.add("session-1");

        assertFalse(added, "Adding a duplicate must return false");
        assertEquals(1, sessions.size(), "Set size must remain 1 after duplicate add");
    }

    @Test
    @DisplayName("Concurrent access from multiple threads is thread-safe")
    void concurrentAccess_threadSafe() throws InterruptedException {
        int threadCount = 50;
        int sessionsPerThread = 20;
        int expectedTotal = threadCount * sessionsPerThread;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start at the same time
                    for (int s = 0; s < sessionsPerThread; s++) {
                        sessions.add("thread-" + threadId + "-session-" + s);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads simultaneously
        doneLatch.await();
        executor.shutdown();

        assertEquals(expectedTotal, sessions.size(),
                "All " + expectedTotal + " unique sessions must be present after concurrent adds");

        // Verify a sampling of entries
        assertTrue(sessions.contains("thread-0-session-0"));
        assertTrue(sessions.contains("thread-" + (threadCount - 1) + "-session-" + (sessionsPerThread - 1)));
    }
}
