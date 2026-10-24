package com.linkforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Legacy-volume upgrade path: a database created the pre-Flyway way (all tables
 * from schema.sql, no flyway_schema_history) must be baselined at 1 on app start,
 * skip V1, and stay fully usable. The complementary empty-db path (Flyway applies
 * V1) is exercised by every other IT, which now boots without an init script.
 *
 * Standalone on purpose: it must NOT share the suite's already-migrated MySQL
 * singleton. RabbitMQ is never contacted (test profile: listener off, async off).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-flyway:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class FlywayBaselineIT {

    static final MySQLContainer<?> LEGACY_MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("linkforge")
            .withUsername("linkforge")
            .withPassword("linkforge_pass")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
            .withInitScript("db/schema.sql")
            .withStartupTimeout(java.time.Duration.ofMinutes(4));

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        LEGACY_MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void legacyProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", LEGACY_MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", LEGACY_MYSQL::getUsername);
        registry.add("spring.datasource.password", LEGACY_MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper om;

    @Test
    void legacyVolumeIsBaselinedNotRemigrated() {
        var rows = jdbc.queryForList("SELECT version, type, description, success FROM flyway_schema_history");
        assertEquals(1, rows.size(), "baseline row only: " + rows);
        Map<String, Object> baseline = rows.get(0);
        assertEquals("1", String.valueOf(baseline.get("version")));
        assertEquals("BASELINE", String.valueOf(baseline.get("type")));
        assertEquals("1", String.valueOf(baseline.get("success")));
        // V1 was skipped, so the schema still carries zero data rows beyond the bootstrap:
        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM lf_tenant)", Boolean.class),
                "no seed data expected on this volume");
    }

    @Test
    void baselinedSchemaServesTheCorePath() throws Exception {
        String json = om.writeValueAsString(java.util.Map.of(
                "tenantName", "flybase" + System.currentTimeMillis(),
                "username", "flyu" + System.currentTimeMillis(),
                "password", "fly-pass-123456"));
        var register = rest.postForEntity("/api/auth/register",
                new org.springframework.http.HttpEntity<>(json, jsonHeaders()), String.class);
        assertTrue(register.getStatusCode().is2xxSuccessful(), register.getBody());
        String token = om.readTree(register.getBody()).path("data").path("token").asText();

        var create = rest.exchange("/api/links", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/flyway")),
                        authHeaders(token)), String.class);
        assertTrue(create.getStatusCode().is2xxSuccessful(), create.getBody());
        String code = om.readTree(create.getBody()).path("data").path("code").asText();

        var redirect = rest.getForEntity("/r/" + code, String.class);
        assertEquals(302, redirect.getStatusCode().value());
    }

    private org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private org.springframework.http.HttpHeaders authHeaders(String token) {
        org.springframework.http.HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
