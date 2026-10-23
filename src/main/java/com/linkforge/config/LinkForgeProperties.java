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
        /** When true, also publish click events to RabbitMQ. */
        private boolean asyncClickEnabled = false;
        /** D7=A: keep the sync lf_click_log write alongside the async path. */
        private boolean syncClickEnabled = true;
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
        private String keyPrefix = "linkforge:rl:";
    }
}
