package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * B3: signed unlock cookies, stateless (no server session, no revocation table).
 * Value = {@code {expEpochSeconds}.{sig}} with
 * {@code sig = Base64url(HMAC-SHA256(key, linkId|code|exp|pwdVer))} where
 * {@code pwdVer} is derived from the CURRENT access password hash — changing or
 * clearing the password instantly invalidates every outstanding cookie (D-Bw345-35).
 * The key is derived from the JWT secret (D-Bw345-36): no new env, nothing in the
 * repository, and rotating the JWT secret expires all unlock cookies.
 */
@Component
public class LinkAccessTokens {

    private static final String KEY_DERIVE_INFO = "linkforge:link-access:v1";
    private static final char SEPARATOR = '|';

    private final byte[] hmacKey;
    private final long ttlSeconds;

    public LinkAccessTokens(LinkForgeProperties properties) {
        this.hmacKey = hmacSha256(
                properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8),
                KEY_DERIVE_INFO.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = properties.getLinkAccess().getTtlSeconds();
    }

    public String cookieName(String code) {
        return "lf_ac_" + code;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(long linkId, String code, String passwordHash) {
        long exp = epochSecond() + ttlSeconds;
        String payload = payload(linkId, code, exp, pwdVer(passwordHash));
        return exp + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacSha256(hmacKey, payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Constant-time verify; false on any malformed input, expiry, or pwdVer drift. */
    public boolean validate(String cookieValue, long linkId, String code, String passwordHash) {
        if (cookieValue == null) {
            return false;
        }
        int dot = cookieValue.indexOf('.');
        if (dot <= 0 || dot == cookieValue.length() - 1) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(cookieValue.substring(0, dot));
        } catch (NumberFormatException ex) {
            return false;
        }
        if (exp <= epochSecond()) {
            return false;
        }
        byte[] provided = Base64.getUrlDecoder().decode(cookieValue.substring(dot + 1));
        byte[] expected = hmacSha256(hmacKey,
                payload(linkId, code, exp, pwdVer(passwordHash)).getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(provided, expected);
    }

    private static String payload(long linkId, String code, long exp, String pwdVer) {
        return linkId + String.valueOf(SEPARATOR) + code + SEPARATOR + exp + SEPARATOR + pwdVer;
    }

    /** First 8 bytes of SHA-256 of the current hash — a cheap binding to "this password". */
    private static String pwdVer(String passwordHash) {
        byte[] digest = sha256(passwordHash == null ? "" : passwordHash);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return hex.toString();
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static long epochSecond() {
        return System.currentTimeMillis() / 1000;
    }
}
