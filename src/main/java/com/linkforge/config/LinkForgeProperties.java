package com.linkforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "linkforge")
public class LinkForgeProperties {

    private Jwt jwt = new Jwt();
    /** C1 wave B: origin scanners see in QR/share material; trailing slash trimmed on use. */
    private String publicBaseUrl = "http://localhost:8080";
    private Qr qr = new Qr();
    private ShortLink shortLink = new ShortLink();
    private Mq mq = new Mq();
    private Bootstrap bootstrap = new Bootstrap();
    private RateLimit rateLimit = new RateLimit();
    private Bloom bloom = new Bloom();
    /** C2 wave A (D-C2-3..8): Redis pub/sub cache bus — Bloom put broadcast now, L1 invalidate in wave C; false = silent no-op. */
    private boolean cacheBusEnabled = true;
    private SingleFlight singleFlight = new SingleFlight();
    private L1 l1 = new L1();
    private ApiKey apiKey = new ApiKey();
    private Webhook webhook = new Webhook();
    private ClickFilter clickFilter = new ClickFilter();
    private Phishing phishing = new Phishing();
    private LinkAccess linkAccess = new LinkAccess();
    private Metrics metrics = new Metrics();
    private Retention retention = new Retention();

    @Data
    public static class Retention {
        /**
         * Delete SENT {@code lf_outbox} and {@code lf_webhook_delivery} rows older than
         * this many days. {@code <= 0} disables the job. PENDING, FAILED, and in-flight
         * rows are never deleted. Outbox {@code sent_at} is compared on the DB clock
         * (the column is written with {@code NOW()}); webhook {@code sent_at} is compared
         * on the JVM clock (the column is written from Java).
         */
        private int sentDays = 7;
        /** @Scheduled reads millis (its placeholder parsing knows no Boot duration style). */
        private long cleanupIntervalMs = 3_600_000L;
        private long cleanupInitialDelayMs = 60_000L;
        private int cleanupBatchSize = 1000;
        /** Cap DELETE batches per tick so one pass cannot pin a scheduler thread. */
        private int cleanupMaxPasses = 10;
    }

    @Data
    public static class Metrics {
        /** B1: bearer token for /actuator/prometheus; empty = the endpoint rejects everyone. */
        private String scrapeToken = "";
        /** BacklogGauges sampling period (DB COUNT queries run off the scrape path). */
        private long backlogSampleIntervalMs = 15000;
    }

    @Data
    public static class LinkAccess {
        /** B3: unlock cookie TTL and the two-tier brute-force windows. */
        private long ttlSeconds = 3600;
        private int unlockIpMax = 5;
        private int unlockCodeMax = 50;
        private int unlockWindowSeconds = 300;
    }

    @Data
    public static class Phishing {
        /** B9: a locally maintained domain blocklist/warnlist + userinfo rule. Honest
         * scope: no reputation service, no content scan, the service is still an open redirect. */
        private boolean enabled = true;
        /** Env values replace the whole list (comma separated); defaults must stay empty so
         * existing IT target domains are untouched. */
        private java.util.List<String> blockDomains = new java.util.ArrayList<>();
        private java.util.List<String> warnDomains = new java.util.ArrayList<>();
        /** Optional file: one domain per line, # comments, "!warn " prefix = warn level;
         * hot-reloaded by mtime. Empty = not loaded. */
        private String blocklistFile = "";
        /** @Scheduled reads millis (its placeholder parsing knows no Boot duration style). */
        private long reloadIntervalMs = 30000;
        /** Create/update rejects userinfo targets (https://user@host); existing rows only warn. */
        private boolean rejectUserinfo = true;
    }

    @Data
    public static class Jwt {
        private String secret;
        private long expireHours = 24;
    }

    @Data
    public static class Qr {
        /** D-C1-1: HMAC key for share links; blank derives deterministically from
         * HMAC-SHA256(jwtSecret, "linkforge:qr-share:v1") — no operator burden. */
        private String shareSecret = "";
        /** Default share-link lifetime; the request body may clamp it to [1..3600]. */
        private int shareTtlSeconds = 300;
    }

    @Data
    public static class ShortLink {
        private long cacheTtlSeconds = 3600;
        /** B-wave1: positive-cache TTL = base + uniform [0, jitter] (upward only). */
        private long cacheTtlJitterSeconds = 300;
        /** B-wave1: negative-cache (confirmed-absent codes) base TTL; <= 0 disables the negative cache. */
        private long absentTtlSeconds = 60;
        private long absentTtlJitterSeconds = 30;
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
        /** C2 wave B (D-C2-9): scheduled full rebuild switch; false = the job returns immediately. */
        private boolean rebuildEnabled = true;
        /** @Scheduled reads millis (its placeholder parsing knows no Boot duration style). */
        private long rebuildIntervalMs = 86_400_000L;
    }

    @Data
    public static class SingleFlight {
        /** A10: merge concurrent same-code hot-path row reads into one in-flight DB read. */
        private boolean enabled = true;
        /** Follower wait cap; on expiry the follower does its own DB read (fail-open). */
        private long waitTimeoutMs = 500;
    }

    @Data
    public static class L1 {
        /** B-wave1 B8: process-local URL cache in front of Redis; a hit never skips the DB row read. */
        private boolean enabled = true;
        private long maximumSize = 10_000;
        /** Must stay well under the Redis positive-cache TTL. */
        private long ttlSeconds = 10;
    }

    @Data
    public static class ApiKey {
        /** B-wave2 B4: quotas fall back to these when the key row stores NULL. */
        private int defaultQuotaPerMinute = 120;
        private int defaultQuotaPerDay = 10_000;
        private int maxActivePerUser = 10;
        /** Bw2-N4: a key creator cannot raise a key above these caps. */
        private int maxQuotaPerMinute = 1_000;
        private int maxQuotaPerDay = 100_000;
    }

    @Data
    public static class Webhook {
        /** B-wave2 B5: click webhooks off the A7 outbox chain. */
        private boolean enabled = true;
        /** Relay drain switch; test contexts default it off so only one relay race-drains the shared table. */
        private boolean relayEnabled = true;
        /** Base64 of a 32-byte AES-256 master key; missing key degrades the feature off. */
        private String secretMasterKey = "";
        private long relayFixedDelayMs = 2000;
        private long relayInitialDelayMs = 5000;
        private int relayBatchSize = 50;
        private int maxAttempts = 8;
        private long backoffBaseSeconds = 10;
        private long backoffMaxSeconds = 3600;
        private int connectTimeoutMs = 2000;
        private int requestTimeoutMs = 5000;
        /** IT-only escape hatch; must never be true in prod (D-Bw2-15). */
        private boolean allowPrivateTargets = false;
        /** C1 wave C (D-C1-23): hard cap on digest parts per window; overflow sets truncated. */
        private int aggregateMaxParts = 10;
    }

    @Data
    public static class ClickFilter {
        /** B2: prefetch headers and named preview bots get the normal 302 but record no click. */
        private boolean enabled = true;
        private boolean prefetchHeadersEnabled = true;
        /**
         * Lowercase substrings matched against User-Agent; the env value replaces the whole
         * list. Keep out curl/wget/java//okhttp and the generic "bot" — they would swallow
         * ITs, drill scripts and real tooling (D-Bw345-3).
         */
        private List<String> botUaTokens = List.of(
                "slackbot", "slack-imgproxy", "facebookexternalhit", "facebookcatalog", "twitterbot",
                "telegrambot", "whatsapp", "discordbot", "linkedinbot", "skypeuripreview",
                "pinterestbot", "redditbot", "embedly", "iframely", "googlebot", "bingbot",
                "bingpreview", "applebot", "yandexbot", "duckduckbot", "baiduspider");
    }

    @Data
    public static class RateLimit {
        /** Sliding-window-log per-IP limit for open registration (Redis ZSET + Lua, Redis TIME clock). */
        private int registerMax = 5;
        private int registerWindowSeconds = 600;
        /** Sliding-window-log per-user limit for link creation. */
        private int createMax = 30;
        private int createWindowSeconds = 60;
        /** D-C3-22: per-user limit for POST /api/links/import — one token per request, never per row. */
        private int importMax = 5;
        private int importWindowSeconds = 60;
        /** Sliding-window-log per-IP limit for public redirects. */
        private int redirectMax = 300;
        private int redirectWindowSeconds = 60;
        /** TTL of the Idempotency-Key -> code mapping. */
        private long idempotencyTtlSeconds = 86400;
        /** Honor X-Forwarded-For only behind a trusted reverse proxy; spoofable otherwise. */
        private boolean trustXff = false;
        private String keyPrefix = "linkforge:rl:";
    }
}
