package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkAccessTokenTest {

    private static LinkAccessTokens tokens(String jwtSecret, long ttl) {
        LinkForgeProperties props = new LinkForgeProperties();
        props.getJwt().setSecret(jwtSecret);
        props.getLinkAccess().setTtlSeconds(ttl);
        return new LinkAccessTokens(props);
    }

    private static final String HASH_A = "$2a$10abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK";
    private static final String HASH_B = "$2b$10zyxwvutsrqponmlkjihgfedcba9876543210ZYXWVUTSRQPONMLK";

    @Test
    void issueThenValidateRoundTrips() {
        LinkAccessTokens t = tokens("test-secret-at-least-32-characters-long!!", 3600);
        String cookie = t.issue(42L, "AbC123", HASH_A);
        assertTrue(cookie.matches("\\d+\\.[A-Za-z0-9_-]+"), "shape exp.sig: " + cookie);
        assertTrue(t.validate(cookie, 42L, "AbC123", HASH_A));
    }

    @Test
    void tamperedOrForeignCookiesAreRejected() {
        LinkAccessTokens t = tokens("test-secret-at-least-32-characters-long!!", 3600);
        String cookie = t.issue(42L, "AbC123", HASH_A);
        String sig = cookie.substring(cookie.indexOf('.') + 1);
        assertFalse(t.validate("999999999." + sig, 42L, "AbC123", HASH_A), "wrong exp rejected");
        assertFalse(t.validate(cookie, 43L, "AbC123", HASH_A), "another link id rejected");
        assertFalse(t.validate(cookie, 42L, "other", HASH_A), "another code rejected");
        assertFalse(t.validate("garbage", 42L, "AbC123", HASH_A));
        assertFalse(t.validate(null, 42L, "AbC123", HASH_A));
        // flipping a sig byte must fail
        char[] chars = sig.toCharArray();
        chars[0] = chars[0] == 'A' ? 'B' : 'A';
        assertFalse(t.validate(cookie.substring(0, cookie.indexOf('.') + 1) + new String(chars),
                42L, "AbC123", HASH_A));
    }

    @Test
    void expiredCookiesAreRejected() {
        LinkAccessTokens t = tokens("test-secret-at-least-32-characters-long!!", 1);
        String cookie = t.issue(42L, "AbC123", HASH_A);
        assertTrue(t.validate(cookie, 42L, "AbC123", HASH_A), "valid at issue time");
        assertFalse(t.validate(expiredWithValidSig(t), 42L, "AbC123", HASH_A),
                "an expired exp with a correct signature is still rejected");
    }

    /** Hand-builds {exp-10}.{correct sig} to prove expiry is checked before the signature. */
    private static String expiredWithValidSig(LinkAccessTokens t) {
        try {
            Field key = LinkAccessTokens.class.getDeclaredField("hmacKey");
            key.setAccessible(true);
            byte[] hmacKey = (byte[]) key.get(t);
            long exp = System.currentTimeMillis() / 1000 - 10;
            String pwdVer = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(HASH_A.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 8);
            String payload = "42|AbC123|" + exp + "|" + pwdVer;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return exp + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void passwordChangeInvalidatesOldCookies() {
        LinkAccessTokens t = tokens("test-secret-at-least-32-characters-long!!", 3600);
        String cookie = t.issue(42L, "AbC123", HASH_A);
        assertTrue(t.validate(cookie, 42L, "AbC123", HASH_A));
        assertFalse(t.validate(cookie, 42L, "AbC123", HASH_B), "pwdVer drift invalidates");
        assertFalse(t.validate(cookie, 42L, "AbC123", null), "cleared password invalidates");
    }

    @Test
    void keyDerivationFollowsTheJwtSecret() {
        LinkAccessTokens a = tokens("secret-one-at-least-32-characters-long!!!", 3600);
        LinkAccessTokens b = tokens("secret-two-at-least-32-characters-long!!!", 3600);
        String cookie = a.issue(42L, "AbC123", HASH_A);
        assertTrue(a.validate(cookie, 42L, "AbC123", HASH_A));
        assertFalse(b.validate(cookie, 42L, "AbC123", HASH_A), "another JWT secret rejects");
    }

    @Test
    void ttlComesFromConfiguration() {
        assertEquals(120, tokens("test-secret-at-least-32-characters-long!!", 120).ttlSeconds());
        assertThrows(Exception.class, () -> tokens(null, 120),
                "a missing JWT secret must fail loudly, never silently sign with a default");
    }
}
