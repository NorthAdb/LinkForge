package com.linkforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "linkforge")
public class LinkForgeProperties {

    private Jwt jwt = new Jwt();
    private ShortLink shortLink = new ShortLink();
    private Mq mq = new Mq();
    private Bootstrap bootstrap = new Bootstrap();
    private RateLimit rateLimit = new RateLimit();
    private Bloom bloom = new Bloom();

    @Data
    public static class Jwt {
        private String secret;
        private long expireHours = 24;
    }

    @Data
    public static class ShortLink {
        private long cacheTtlSeconds = 3600;
        private int codeLength = 7;
    }

    @Data
    public static class Mq {
        /** When true, click events go through the outbox+relay instead of a hot-path publish. */
        private boolean asyncClickEnabled = false;
        /** D7=A: keep the sync lf_click_log write alongside the outbox path. */
        private boolean syncClickEnabled = true;
        /** A7 relay: drains PENDING outbox rows to RabbitMQ. */
        private boolean relayEnabled = true;
        private int relayBatchSize = 100;
        private long relayFixedDelayMs = 2000;
        private long relayInitialDelayMs = 3000;
        /** A7 audit N3: attempts beyond this mark the row FAILED instead of retrying forever. */
        private int relayMaxAttempts = 30;
    }

    @Data
    public static class Bootstrap {
        private boolean enabled = true;
        private String tenantName = "demo";
        private String username = "admin";
        /** Required via LINKFORGE_BOOTSTRAP_PASSWORD. No default in source. */
        private String password;
    }

    @Data
    public static class Bloom {
        /** A9: a negative answer is authoritative (code never created) -> 404 before MySQL. */
        private boolean enabled = true;
        /** Capacity hint; inserting well beyond this degrades the false-positive rate (README). */
        private int expectedInsertions = 100_000;
        /** Desired false-positive probability; false positives just fall through to cache+DB. */
        private double fpp = 0.0001;
    }

    @Data
    public static class RateLimit {
        /** Fixed-window per-IP limit for open registration. */
        private int registerMax = 5;
        private int registerWindowSeconds = 600;
        /** Fixed-window per-user limit for link creation. */
        private int createMax = 30;
        private int createWindowSeconds = 60;
        /** Fixed-window per-IP limit for public redirects. */
        private int redirectMax = 300;
        private int redirectWindowSeconds = 60;
        /** TTL of the Idempotency-Key -> code mapping. */
        private long idempotencyTtlSeconds = 86400;
        /** Honor X-Forwarded-For only behind a trusted reverse proxy; spoofable otherwise. */
        private boolean trustXff = false;
        private String keyPrefix = "linkforge:rl:";
    }
}
