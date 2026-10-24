package com.linkforge.webhook.support;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignerTest {

    @Test
    void signatureMatchesAnIndependentHmacComputation() throws Exception {
        String secret = "whsec_abcdef";
        long ts = 1_798_000_000L;
        String body = "{\"type\":\"link.clicked\"}";

        String signature = WebhookSigner.sign(secret, ts, body);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : expected) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("v1=" + hex, signature);
        assertTrue(signature.startsWith("v1="));
    }

    @Test
    void differentTimestampBodyOrSecretChangeTheSignature() {
        String a = WebhookSigner.sign("s1", 100L, "body");
        assertNotEquals(a, WebhookSigner.sign("s1", 101L, "body"), "timestamp is signed");
        assertNotEquals(a, WebhookSigner.sign("s1", 100L, "body2"), "body is signed");
        assertNotEquals(a, WebhookSigner.sign("s2", 100L, "body"), "secret is signed");
    }
}
