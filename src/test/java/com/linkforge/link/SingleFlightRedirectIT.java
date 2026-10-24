package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.support.ShortLinkRowLoader;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * A10: the redirect hot path merges concurrent same-code row reads into ONE
 * in-flight DB read. The overlap is manufactured by making the spied row loader
 * sleep, so the burst has a wide in-flight window to land in (row reads alone are
 * 1-2 ms and would not overlap reliably). No test hooks in production config.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-sf:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class SingleFlightRedirectIT extends AbstractIntegrationTest {

    @SpyBean
    private ShortLinkRowLoader rowLoader;

    @Autowired
    private LinkForgeProperties properties;

    @Autowired
    private MeterRegistry meterRegistry;

    private final AtomicLong rowReads = new AtomicLong();

    @BeforeEach
    void resetSpyAndCounter() {
        Mockito.reset(rowLoader);
        rowReads.set(0);
    }

    private void spyRowReads(int delayMs) {
        doAnswer(inv -> {
            rowReads.incrementAndGet();
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            return inv.callRealMethod();
        }).when(rowLoader).load(anyString());
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    void concurrentRedirectsShareExactlyOneRowRead() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/sf-burst");
        spyRowReads(300);

        int n = 24;
        double loads0 = counter("linkforge.singleflight.loads");
        double shared0 = counter("linkforge.singleflight.shared");
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return get("/r/" + code, null).getStatusCode().value();
                }));
            }
            go.countDown();
            for (Future<Integer> f : futures) {
                assertEquals(302, f.get(15, TimeUnit.SECONDS), "every rider must land on the real URL");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, rowReads.get(), "the whole burst must cost exactly one row read");
        assertEquals(1.0, counter("linkforge.singleflight.loads") - loads0, "loads must grow by one lead");
        assertEquals(n - 1.0, counter("linkforge.singleflight.shared") - shared0, "every other rider must share");
    }

    @Test
    void mergeDisabledEveryRequestReadsItsOwnRow() throws Exception {
        properties.getSingleFlight().setEnabled(false);
        try {
            String token = registerForToken();
            String code = createLink(token, "https://example.com/sf-off");
            spyRowReads(0);

            int n = 12;
            double loads0 = counter("linkforge.singleflight.loads");
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return get("/r/" + code, null).getStatusCode().value();
                    }));
                }
                go.countDown();
                for (Future<Integer> f : futures) {
                    assertEquals(302, f.get(15, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(n, rowReads.get(), "disabled merger must read per request");
            assertEquals(0.0, counter("linkforge.singleflight.loads") - loads0,
                    "disabled merger must not count leads");
        } finally {
            properties.getSingleFlight().setEnabled(true);
        }
    }

    @Test
    void disableDuringInFlightReadIsNotRiddenByLaterRequests() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/sf-forget");
        spyRowReads(300);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> slow = pool.submit(() -> get("/r/" + code, null).getStatusCode().value());
            await().atMost(java.time.Duration.ofSeconds(5)).until(() -> rowReads.get() >= 1);

            assertEquals(200, postJson("/api/links/" + code + "/disable", "{}", token).getStatusCode().value());
            // the disable forgot the in-flight entry: this GET must re-lead on a fresh row
            assertEquals(404, get("/r/" + code, null).getStatusCode().value(),
                    "a request after disable must not ride the pre-disable in-flight read");
            slow.get(15, TimeUnit.SECONDS); // pre-disable read (302) or fresh read (404) — window is documented
        } finally {
            pool.shutdownNow();
        }
    }
}
