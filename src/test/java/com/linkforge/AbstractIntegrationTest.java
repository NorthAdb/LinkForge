package com.linkforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Helpers for {@code @SpringBootTest(RANDOM_PORT)} integration tests. Concrete classes
 * declare their own {@code @SpringBootTest}/{@code @ActiveProfiles("test")} so each can
 * tune properties without forking this base.
 *
 * Dependencies come from per-JVM Testcontainers singletons (started once, shared by
 * every context), so the suite needs only a Docker daemon — no host Compose stack.
 */
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
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

    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withAdminUser("linkforge")
            .withAdminPassword("linkforge_pass");

    static {
        MYSQL.start();
        REDIS.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672).toString());
        registry.add("spring.rabbitmq.username", () -> "linkforge");
        registry.add("spring.rabbitmq.password", () -> "linkforge_pass");
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper om;

    @BeforeEach
    void usePatchCapableRequestFactory() {
        // The default factory rejects PATCH ("Invalid HTTP method"); the JDK HttpClient one does not.
        rest.getRestTemplate().setRequestFactory(
                new JdkClientHttpRequestFactory(java.net.http.HttpClient.newHttpClient()));
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /** Registers a fresh tenant+user and returns its JWT. */
    protected String registerForToken() throws Exception {
        String json = om.writeValueAsString(java.util.Map.of(
                "tenantName", unique("it"),
                "username", unique("u"),
                "password", "it-pass-123456"));
        ResponseEntity<String> resp = postJson("/api/auth/register", json, null);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("register failed: " + resp.getStatusCode() + " " + resp.getBody());
        }
        return om.readTree(resp.getBody()).path("data").path("token").asText();
    }

    /** Creates a short link and returns its code. */
    protected String createLink(String token, String originalUrl) throws Exception {
        String json = om.writeValueAsString(java.util.Map.of("originalUrl", originalUrl));
        ResponseEntity<String> resp = postJson("/api/links", json, token);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("create link failed: " + resp.getStatusCode() + " " + resp.getBody());
        }
        return om.readTree(resp.getBody()).path("data").path("code").asText();
    }

    protected ResponseEntity<String> postJson(String uri, String json, String token) {
        return exchange(HttpMethod.POST, uri, json, token);
    }

    protected ResponseEntity<String> postJsonWithHeader(String uri, String json, String token,
                                                        String headerName, String headerValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.set(headerName, headerValue);
        return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    protected ResponseEntity<String> patchJson(String uri, String json, String token) {
        return exchange(HttpMethod.PATCH, uri, json, token);
    }

    protected ResponseEntity<String> get(String uri, String token) {
        return exchange(HttpMethod.GET, uri, null, token);
    }

    protected ResponseEntity<String> delete(String uri, String token) {
        return exchange(HttpMethod.DELETE, uri, null, token);
    }

    protected ResponseEntity<String> getWithHeader(String uri, String token, String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.set(name, value);
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String uri, String json, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        return rest.exchange(uri, method, entity, String.class);
    }

    protected JsonNode body(ResponseEntity<String> resp) throws Exception {
        return om.readTree(resp.getBody());
    }
}
