package com.linkforge.link.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Audit fix M5: in-flight lease for CSV import idempotency. The settled
 * result cache (batch 3b) alone cannot stop two CONCURRENT same-key imports —
 * both read a miss and both execute. The lease makes the idem-import key a
 * small state machine instead:
 *
 * <pre>
 *   (absent) --SET NX--> pending:&lt;owner&gt;:&lt;fileSha256&gt; --CAS--> settled JSON (3b format)
 *                             |
 *                             +-- CAS delete (only before any row write)
 *                             +-- TTL expiry (120s)
 * </pre>
 *
 * Settle and release compare the FULL pending value (owner token + file
 * fingerprint) through Lua, so a stale owner whose lease expired can neither
 * overwrite nor delete a newer attempt's lease or result. Scope limit, by
 * design (audit §12.4 gate): the lease covers concurrent execution under a
 * healthy Redis — it is NOT crash recovery. If the holder dies mid-import,
 * the lease expires and a later retry may re-execute; no exactly-once claim
 * is made for that case.
 */
@Component
public class ImportIdempotencyLease {

    public static final String PENDING_PREFIX = "pending:";
    public static final Duration LEASE_TTL = Duration.ofSeconds(120);

    // Script text is set literally (never setLocation): the text must be
    // available without any runtime resource read on an arbitrary thread —
    // the same lesson RedisRateLimiter's static loader documents.
    private static final DefaultRedisScript<Long> SETTLE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

    static {
        SETTLE_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then "
                        + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 "
                        + "else return 0 end");
        SETTLE_SCRIPT.setResultType(Long.class);
        RELEASE_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then "
                        + "return redis.call('del', KEYS[1]) "
                        + "else return 0 end");
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public ImportIdempotencyLease(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** One import attempt's lease: a random owner token plus the file fingerprint. */
    public record Lease(String ownerToken, String fileFingerprint) {

        /** The exact Redis value this lease holds while in flight. */
        public String pendingValue() {
            return PENDING_PREFIX + ownerToken + ":" + fileFingerprint;
        }
    }

    /**
     * Takes the lease with SET NX. Returns the lease when won, or null when
     * the key already holds anything (a pending lease or a settled result) —
     * classifying that value is the caller's job.
     */
    public Lease tryAcquire(String key, String fileFingerprint) {
        Lease lease = new Lease(UUID.randomUUID().toString(), fileFingerprint);
        Boolean won = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, lease.pendingValue(), LEASE_TTL);
        return Boolean.TRUE.equals(won) ? lease : null;
    }

    /** The raw current value of the key (another attempt's pending or a settled result). */
    public String peek(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * Replaces MY pending value with the settled result (Lua CAS on the full
     * pending value). Returns false when the key no longer holds my lease —
     * it expired and a newer attempt owns it now; that attempt's value wins.
     */
    public boolean settle(String key, Lease lease, String settledValue, Duration settledTtl) {
        Long replaced = stringRedisTemplate.execute(SETTLE_SCRIPT, List.of(key),
                lease.pendingValue(), settledValue, String.valueOf(settledTtl.toMillis()));
        return replaced != null && replaced == 1L;
    }

    /**
     * Deletes the key only while it still holds MY pending value (Lua CAS).
     * Returns false when a newer attempt has taken the key over.
     */
    public boolean release(String key, Lease lease) {
        Long deleted = stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key), lease.pendingValue());
        return deleted != null && deleted == 1L;
    }

    public static boolean isPending(String value) {
        return value != null && value.startsWith(PENDING_PREFIX);
    }

    /** The file fingerprint inside a pending value, or null when it is not one. */
    public static String pendingFingerprint(String value) {
        if (!isPending(value)) {
            return null;
        }
        int split = value.indexOf(':', PENDING_PREFIX.length());
        return split > PENDING_PREFIX.length() ? value.substring(split + 1) : null;
    }
}
