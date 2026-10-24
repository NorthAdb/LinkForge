package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightTest {

    private final LinkForgeProperties properties = new LinkForgeProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private SingleFlight<String, String> newFlight() {
        return new SingleFlight<>(properties, meters);
    }

    /** Blocks the leader inside its loader until released; counts executions. */
    private static final class GatedLoader implements Supplier<String> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final String value;

        GatedLoader(String value) {
            this.value = value;
        }

        @Override
        public String get() {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return value;
        }
    }

    private static void awaitAllWaiting(List<Thread> threads) {
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> threads.stream()
                .allMatch(t -> t.getState() == Thread.State.TIMED_WAITING));
    }

    @Test
    void concurrentCallersShareExactlyOneLoad() throws Exception {
        SingleFlight<String, String> flight = newFlight();
        GatedLoader loader = new GatedLoader("row");
        Thread leader = new Thread(() -> flight.run("code", loader));
        leader.start();
        assertTrue(loader.started.await(5, TimeUnit.SECONDS), "leader must be in flight");

        List<java.util.concurrent.FutureTask<String>> tasks = new ArrayList<>();
        List<Thread> followers = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(
                    () -> flight.run("code", () -> "FOLLOWER-MUST-NOT-RUN"));
            tasks.add(task);
            followers.add(new Thread(task, "sf-follower-" + i));
        }
        followers.forEach(Thread::start);
        awaitAllWaiting(followers);

        loader.release.countDown();
        leader.join(5000);
        for (java.util.concurrent.FutureTask<String> task : tasks) {
            assertEquals("row", task.get(5, TimeUnit.SECONDS), "every follower shares the leader's result");
        }
        assertEquals(1, loader.calls.get(), "loader must execute exactly once");

        assertEquals(1.0, meters.get("linkforge.singleflight.loads").counter().count());
        assertEquals(32.0, meters.get("linkforge.singleflight.shared").counter().count());
    }

    @Test
    void leaderFailurePropagatesAndNextCallerReleads() throws Exception {
        SingleFlight<String, String> flight = newFlight();
        GatedLoader failing = new GatedLoader("never");
        Thread leader = new Thread(() -> {
            try {
                flight.run("boom", () -> {
                    failing.get();
                    throw new IllegalStateException("db down");
                });
            } catch (RuntimeException expected) {
                // leader rethrows
            }
        });
        leader.start();
        // wait until the leader is inside the gated section
        assertTrue(failing.started.await(5, TimeUnit.SECONDS));

        List<java.util.concurrent.FutureTask<String>> tasks = new ArrayList<>();
        List<Thread> followers = new ArrayList<>();
        AtomicReference<Throwable> followerCause = new AtomicReference<>();
        for (int i = 0; i < 4; i++) {
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(
                    () -> flight.run("boom", () -> "SELF"));
            tasks.add(task);
            followers.add(new Thread(task, "sf-follower-" + i));
        }
        followers.forEach(Thread::start);
        awaitAllWaiting(followers);
        failing.release.countDown();
        leader.join(5000);
        for (java.util.concurrent.FutureTask<String> task : tasks) {
            java.util.concurrent.ExecutionException ex =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, ex.getCause());
            assertEquals("db down", ex.getCause().getMessage());
            followerCause.set(ex.getCause());
        }

        // the settled entry must be gone: the next caller leads a fresh flight
        assertEquals("second", flight.run("boom", () -> "second"));
    }

    @Test
    void followerTimeoutFallsBackToSelfRead() throws Exception {
        properties.getSingleFlight().setWaitTimeoutMs(50);
        SingleFlight<String, String> flight = newFlight();
        GatedLoader leaderLoader = new GatedLoader("leader-value");
        Thread leader = new Thread(() -> flight.run("slow", leaderLoader));
        leader.start();
        assertTrue(leaderLoader.started.await(5, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        String self = flight.run("slow", () -> "self-read");
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals("self-read", self, "timed-out follower must do its own read");
        assertTrue(waitedMs >= 40, "follower must actually wait before degrading");
        assertEquals(1.0, meters.get("linkforge.singleflight.timeout").counter().count());
        assertEquals(0.0, meters.find("linkforge.singleflight.shared").counter() == null
                ? 0.0 : meters.get("linkforge.singleflight.shared").counter().count());

        leaderLoader.release.countDown();
        leader.join(5000);
        assertEquals("again", flight.run("slow", () -> "again"),
                "settled flight leaves no residue: the next call leads a fresh flight");
    }

    @Test
    void forgetDropsTheInFlightEntrySoNextCallerReleads() throws Exception {
        SingleFlight<String, String> flight = newFlight();
        GatedLoader loader = new GatedLoader("stale");
        Thread leader = new Thread(() -> flight.run("w", loader));
        leader.start();
        assertTrue(loader.started.await(5, TimeUnit.SECONDS));

        flight.forget("w"); // write path drops the in-flight read
        AtomicInteger secondLead = new AtomicInteger();
        assertEquals("fresh", flight.run("w", () -> {
            secondLead.incrementAndGet();
            return "fresh";
        }), "after forget the next caller must lead its own flight");
        assertEquals(1, secondLead.get());

        loader.release.countDown();
        leader.join(5000);
    }

    @Test
    void disabledRunsTheLoaderPerCaller() {
        properties.getSingleFlight().setEnabled(false);
        SingleFlight<String, String> flight = newFlight();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 1; i <= 3; i++) {
            assertEquals("v" + i, flight.run("k", () -> "v" + calls.incrementAndGet()));
        }
        assertEquals(3, calls.get(), "disabled merger must not deduplicate anything");
        assertNull_counter("linkforge.singleflight.loads");
    }

    private void assertNull_counter(String name) {
        org.junit.jupiter.api.Assertions.assertNull(meters.find(name).counter(),
                "disabled singleflight must not register " + name);
    }

    private static void assertInstanceOf(Class<?> type, Object actual) {
        assertTrue(type.isInstance(actual), "expected " + type.getSimpleName() + " but got " + actual);
    }
}
