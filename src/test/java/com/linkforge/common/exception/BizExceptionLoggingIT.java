package com.linkforge.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit N1 contract: a failure the wire may only describe with a fixed
 * message stays traceable server-side. When a BizException carries a cause
 * (or is a 5xx), GlobalExceptionHandler emits exactly ONE error log — fixed
 * text + business code + correlation id + the exception stack — while the
 * HTTP body keeps the fixed message and never echoes the cause. No log
 * capture precedent existed in this codebase; this uses a Logback
 * ListAppender in test scope, attached per test and always detached.
 *
 * <p>The trigger is a test-only controller in this class's context: the
 * production endpoints that throw cause-carrying 5xx BizExceptions (bloom
 * rebuild, QR rendering) need infrastructure failures that cannot be forced
 * deterministically over HTTP; the handler/filter path exercised here is
 * the production one end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-bizlog:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class BizExceptionLoggingIT extends AbstractIntegrationTest {

    /** Planted in the cause's message; must appear in the server log's stack
     * and NOWHERE in the HTTP body. */
    static final String MARKER = "n1-trace-marker-4d2c9b";

    @TestConfiguration
    static class TriggerConfig {

        @Bean
        FailureTriggerController failureTriggerController() {
            return new FailureTriggerController();
        }
    }

    @RestController
    static class FailureTriggerController {

        @GetMapping("/api/test-only/biz-failure")
        public String fail(@RequestParam(name = "status", defaultValue = "500") int status) {
            throw new BizException(status, "fixed failure message",
                    new IllegalStateException(MARKER));
        }
    }

    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureHandlerLogs() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void releaseHandlerLogs() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    private long markerEvents() {
        return appender.list.stream()
                .filter(e -> e.getThrowableProxy() != null
                        && e.getThrowableProxy().getMessage() != null
                        && e.getThrowableProxy().getMessage().contains(MARKER))
                .count();
    }

    @Test
    void causeIsTraceableButNotExposed() throws Exception {
        String token = registerForToken();

        ResponseEntity<String> resp = get("/api/test-only/biz-failure", token);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        String rawBody = resp.getBody();
        assertNotNull(rawBody);
        assertEquals("fixed failure message", body(resp).path("message").asText());
        assertFalse(rawBody.contains(MARKER), "the cause must never reach the wire");

        String cid = resp.getHeaders().getFirst("X-Correlation-Id");
        assertNotNull(cid, "CorrelationIdFilter stamps every response");
        assertFalse(cid.isBlank());

        // Exactly one error log from the handler for this failure, carrying
        // the correlation id in MDC and the cause (type + marker) in its
        // stack — the single server-side stack log for the failure.
        var errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(), () -> "captured: " + appender.list);
        ILoggingEvent event = errors.get(0);
        assertEquals(cid, event.getMDCPropertyMap().get("cid"),
                "the log line must carry the same correlation id the client got");
        assertNotNull(event.getThrowableProxy());
        assertEquals(IllegalStateException.class.getName(),
                event.getThrowableProxy().getClassName());
        assertEquals(1, markerEvents(), "the cause stack is logged exactly once");
    }

    @Test
    void causeBearingClientErrorIsLoggedToo() throws Exception {
        String token = registerForToken();

        ResponseEntity<String> resp = get("/api/test-only/biz-failure?status=400", token);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("fixed failure message", body(resp).path("message").asText());
        assertFalse(resp.getBody().contains(MARKER));
        // The OR-cause branch: a 4xx WITH a cause is still logged once —
        // before N1 this failure shape vanished from the server log.
        assertEquals(1, appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count());
        assertEquals(1, markerEvents());
        assertTrue(appender.list.stream()
                .anyMatch(e -> e.getMDCPropertyMap().get("cid") != null));
    }
}
