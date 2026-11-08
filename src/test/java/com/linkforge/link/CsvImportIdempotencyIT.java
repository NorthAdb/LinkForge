package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.support.ImportIdempotencyLease;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit M5 contract tests (reversing the probe
 * {@code concurrentSameKeyImportsBothExecute}): a keyed CSV import now takes
 * an in-flight lease (owner token + file fingerprint, Lua-CAS settle/release)
 * before writing any row, so concurrent same-key imports execute exactly
 * once under a healthy Redis. Lease scope is the audit §12.4 gate: process
 * crash recovery is deliberately NOT covered and not claimed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-impidem:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.import-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class CsvImportIdempotencyIT extends AbstractIntegrationTest {

    private static final String KEY_PREFIX = "linkforge:rl:it-impidem:";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ImportIdempotencyLease leaseComponent;

    private record Registered(long userId, String token) {
    }

    private Registered registerUser() throws Exception {
        var reg = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("t"), "username", unique("u"), "password", "it-pass-123456")), null);
        assertEquals(200, reg.getStatusCode().value(), reg.getBody());
        return new Registered(body(reg).path("data").path("userId").asLong(),
                body(reg).path("data").path("token").asText());
    }

    private ResponseEntity<String> importCsv(String token, String csv, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "import.csv";
            }
        });
        return rest.exchange("/api/links/import", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    /** A CSV whose rows carry distinct, assertable custom codes. */
    private String csvWithCodes(String codePrefix, int rows) {
        String suffix = unique("").substring(0, 6);
        StringBuilder sb = new StringBuilder("original_url,custom_code\n");
        for (int i = 0; i < rows; i++) {
            sb.append("https://example.com/m5-").append(suffix).append('-').append(i)
                    .append(',').append(codePrefix).append(String.format("%02d", i)).append(suffix).append('\n');
        }
        return sb.toString();
    }

    private List<String> codesOf(String csv) {
        List<String> codes = new ArrayList<>();
        for (String line : csv.split("\n")) {
            int comma = line.lastIndexOf(',');
            if (comma > 0 && !line.startsWith("original_url")) {
                codes.add(line.substring(comma + 1).trim());
            }
        }
        return codes;
    }

    private int dbCountForCodes(List<String> codes) {
        int total = 0;
        for (String code : codes) {
            total += jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lf_short_link WHERE code = ?", Integer.class, code);
        }
        return total;
    }

    private long linkCount(String token) throws Exception {
        return body(get("/api/links?page=1&size=100", token)).path("data").path("total").asLong();
    }

    private String idemImportKey(long userId, String key) {
        return KEY_PREFIX + "idem-import:" + userId + ":" + key;
    }

    @Test
    void concurrentSameFileExecutesOnce() throws Exception {
        Registered user = registerUser();
        String csv = csvWithCodes("m5s", 20);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<ResponseEntity<String>> responses = new ArrayList<>();
        try {
            Future<ResponseEntity<String>> first = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return importCsv(user.token(), csv, "conc-same");
            });
            Future<ResponseEntity<String>> second = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return importCsv(user.token(), csv, "conc-same");
            });
            responses.add(first.get(120, TimeUnit.SECONDS));
            responses.add(second.get(120, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(20, linkCount(user.token()),
                "two concurrent same-key imports of a 20-row file create exactly 20 links, never 40");
        assertEquals(20, dbCountForCodes(codesOf(csv)));
        for (ResponseEntity<String> resp : responses) {
            assertTrue(resp.getStatusCode().value() == 200 || resp.getStatusCode().value() == 409,
                    "each contender either executes/replays (200) or is told in-progress (409): "
                            + resp.getStatusCode());
            if (resp.getStatusCode().value() == 409) {
                assertTrue(resp.getBody().contains("IDEMPOTENCY_IN_PROGRESS"), resp.getBody());
            }
        }
        List<ResponseEntity<String>> oks = responses.stream()
                .filter(r -> r.getStatusCode().value() == 200).toList();
        if (oks.size() == 2) {
            assertEquals(body(oks.get(0)).path("data").toString(), body(oks.get(1)).path("data").toString(),
                    "two 200s are only possible as execute + settled replay of the identical outcome");
        }

        // after the race the key is settled: a retry replays without re-importing
        var retry = importCsv(user.token(), csv, "conc-same");
        assertEquals(200, retry.getStatusCode().value(), retry.getBody());
        assertEquals(20, body(retry).path("data").path("successCount").asInt());
        assertEquals(20, linkCount(user.token()));
    }

    @Test
    void concurrentDifferentFileConflictsBeforeMutation() throws Exception {
        Registered user = registerUser();
        String csvA = csvWithCodes("m5da", 10);
        String csvB = csvWithCodes("m5db", 10);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ResponseEntity<String> respA;
        ResponseEntity<String> respB;
        try {
            Future<ResponseEntity<String>> first = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return importCsv(user.token(), csvA, "conc-diff");
            });
            Future<ResponseEntity<String>> second = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return importCsv(user.token(), csvB, "conc-diff");
            });
            respA = first.get(120, TimeUnit.SECONDS);
            respB = second.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Integer> statuses = List.of(respA.getStatusCode().value(), respB.getStatusCode().value());
        assertTrue(statuses.contains(200) && statuses.contains(409),
                "exactly one import wins and one conflicts: " + statuses);
        ResponseEntity<String> loser = respA.getStatusCode().value() == 409 ? respA : respB;
        assertTrue(loser.getBody().contains("IDEMPOTENCY_KEY_REUSED"), loser.getBody());
        List<String> loserCodes = respA.getStatusCode().value() == 409 ? codesOf(csvA) : codesOf(csvB);
        List<String> winnerCodes = respA.getStatusCode().value() == 409 ? codesOf(csvB) : codesOf(csvA);
        assertEquals(0, dbCountForCodes(loserCodes),
                "the losing file must have no business effect — the conflict fires before any row is written");
        assertEquals(10, dbCountForCodes(winnerCodes));
        assertEquals(10, linkCount(user.token()));
    }

    @Test
    void inProgressReturns409WithoutWriting() throws Exception {
        Registered user = registerUser();
        String csv = csvWithCodes("m5ip", 5);
        String fileSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(csv.getBytes(StandardCharsets.UTF_8)));
        // another attempt is mid-flight with this exact file under the key
        redis.opsForValue().set(idemImportKey(user.userId(), "inprog"),
                "pending:some-owner-token:" + fileSha, Duration.ofMinutes(5));

        var resp = importCsv(user.token(), csv, "inprog");
        assertEquals(409, resp.getStatusCode().value(), resp.getBody());
        assertTrue(resp.getBody().contains("IDEMPOTENCY_IN_PROGRESS"), resp.getBody());
        assertEquals(0, linkCount(user.token()), "an in-progress answer writes nothing");
        assertEquals(0, dbCountForCodes(codesOf(csv)));
    }

    @Test
    void structuralFailureReleasesTheLease() throws Exception {
        Registered user = registerUser();
        var bad = importCsv(user.token(), "wrong_header\nhttps://example.com/m5-bad\n", "struct-key");
        assertEquals(400, bad.getStatusCode().value(), bad.getBody());

        // the pre-write failure handed the lease back: the same key works at once
        String csv = csvWithCodes("m5sr", 3);
        var good = importCsv(user.token(), csv, "struct-key");
        assertEquals(200, good.getStatusCode().value(), good.getBody());
        assertEquals(3, body(good).path("data").path("successCount").asInt());
        assertEquals(3, linkCount(user.token()));
    }

    @Test
    void staleOwnerCannotSettleOrRelease() {
        String key = "it-lease:" + unique("");
        ImportIdempotencyLease.Lease ownerA = leaseComponent.tryAcquire(key, "fp-a");
        assertNotNull(ownerA, "the first attempt takes the lease");
        assertNull(leaseComponent.tryAcquire(key, "fp-a"), "a held lease cannot be taken twice");

        // A's lease expires (simulated by deletion); B takes the key over.
        redis.delete(key);
        ImportIdempotencyLease.Lease ownerB = leaseComponent.tryAcquire(key, "fp-a");
        assertNotNull(ownerB);
        assertNotEquals(ownerA.ownerToken(), ownerB.ownerToken());

        assertFalse(leaseComponent.settle(key, ownerA, "settled-by-a", Duration.ofMinutes(1)),
                "a stale owner must not overwrite the newer attempt's lease");
        assertEquals(ownerB.pendingValue(), leaseComponent.peek(key));
        assertFalse(leaseComponent.release(key, ownerA),
                "a stale owner must not delete the newer attempt's lease");
        assertEquals(ownerB.pendingValue(), leaseComponent.peek(key));

        assertTrue(leaseComponent.settle(key, ownerB, "settled-by-b", Duration.ofMinutes(1)),
                "the current owner settles normally");
        assertEquals("settled-by-b", leaseComponent.peek(key));
        redis.delete(key);
    }
}
