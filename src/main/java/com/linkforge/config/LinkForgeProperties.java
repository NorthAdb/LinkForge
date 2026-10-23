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
        /** When true, also publish click events (consumer may be stub). */
        private boolean asyncClickEnabled = false;
    }

    @Data
    public static class Bootstrap {
        private boolean enabled = true;
        private String tenantName = "demo";
        private String username = "admin";
        /** Required via LINKFORGE_BOOTSTRAP_PASSWORD. No default in source. */
        private String password;
    }
}
