package com.linkforge.webhook.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * B-wave2 D-Bw2-11: X-LinkForge-Signature = "v1=" + hex(HMAC-SHA256(secret, timestamp + "." + rawBody)).
 * Receivers verify with a constant-time comparison, a ±300s timestamp tolerance
 * and dedup on X-LinkForge-Event-Id (README shows the pseudo-code).
 */
public final class WebhookSigner {

    public static final String HEADER_EVENT_ID = "X-LinkForge-Event-Id";
    public static final String HEADER_TIMESTAMP = "X-LinkForge-Timestamp";
    public static final String HEADER_SIGNATURE = "X-LinkForge-Signature";

    private WebhookSigner() {
    }

    public static String sign(String secret, long unixTimestampSeconds, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((unixTimestampSeconds + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return "v1=" + hex;
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
