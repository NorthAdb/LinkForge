package com.linkforge.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * B1 (D-Bw345-45): the http.server.requests `uri` tag may only ever carry the
 * controller templates. Requests that fail before MVC dispatch (container-level
 * rejections, aborted connections) would otherwise record their raw path — including
 * real short codes like /r/{code} values — so anything outside the allow-list
 * collapses to UNKNOWN. New endpoints must be added here or their metrics silently
 * merge into UNKNOWN, which is the safe direction.
 */
@Configuration
public class HttpUriTagAllowList {

    private static final Set<String> ALLOWED = Set.of(
            "/r/{code}", "/r/{code}/unlock",
            "/api/links", "/api/links/{code}", "/api/links/{code}/stats",
            "/api/links/{code}/stats/breakdown", "/api/links/{code}/clicks",
            "/api/links/export",
            // C1 wave B: qr.png and qr-share must not collapse into UNKNOWN (javadoc above)
            "/api/links/{code}/qr.png", "/api/links/{code}/qr-share",
            "/api/auth/login", "/api/auth/register",
            "/api/keys", "/api/keys/{id}",
            "/api/webhooks/endpoint", "/api/webhooks/endpoint/{id}",
            "/api/health",
            "/actuator/health", "/actuator/prometheus", "/actuator/metrics", "/actuator/info");

    @Bean
    MeterFilter httpUriTagFilter() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (!"http.server.requests".equals(id.getName())) {
                    return id;
                }
                String uri = id.getTag("uri");
                if (uri == null || ALLOWED.contains(uri)) {
                    return id;
                }
                return id.withTag(Tag.of("uri", "UNKNOWN"));
            }
        };
    }
}
