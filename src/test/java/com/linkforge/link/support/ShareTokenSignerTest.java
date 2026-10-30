package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-C1-2 share token: HMAC-SHA256 over code|exp with the exp never in the past;
 * a blank share-secret derives the key as HMAC-SHA256(jwtSecret, "linkforge:qr-share:v1").
 */
class ShareTokenSignerTest {

    private static LinkForgeProperties props(String shareSecret, String jwtSecret) {
        LinkForgeProperties properties = new LinkForgeProperties();
        properties.setPublicBaseUrl("http://localhost:8080");
        properties.getJwt().setSecret(jwtSecret);
        properties.getQr().setShareSecret(shareSecret);
        return properties;
    }

    private static byte[] hmac(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    private static long inOneHour() {
        return System.currentTimeMillis() / 1000 + 3600;
    }

    @Test
    void signVerifyRoundtripOnTheExactPayload() {
        ShareTokenSigner signer = new ShareTokenSigner(props("", "it-jwt-secret"));
        long exp = inOneHour();
        String sig = signer.sign("ab3xk91", exp);
        assertTrue(sig.matches("[0-9a-f]{64}"), "lowercase hex of one SHA-256 digest");
        assertTrue(signer.verify("ab3xk91", exp, sig));
        assertFalse(signer.verify("other1", exp, sig), "the code is signed");
        assertFalse(signer.verify("ab3xk91", exp + 1, sig), "the exp is signed");
    }

    @Test
    void derivedKeyMatchesTheFrozenFormula() throws Exception {
        String jwtSecret = "it-jwt-secret";
        ShareTokenSigner signer = new ShareTokenSigner(props("", jwtSecret));
        long exp = inOneHour();
        byte[] derivedKey = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), "linkforge:qr-share:v1");
        assertEquals(java.util.HexFormat.of().formatHex(hmac(derivedKey, "ab3xk91|" + exp)),
                signer.sign("ab3xk91", exp),
                "blank share-secret must derive exactly HMAC-SHA256(jwtSecret, label)");
    }

    @Test
    void explicitShareSecretReplacesTheDerivedKey() {
        ShareTokenSigner derived = new ShareTokenSigner(props("", "it-jwt-secret"));
        ShareTokenSigner explicit = new ShareTokenSigner(props("whsec-explicit", "it-jwt-secret"));
        long exp = inOneHour();
        assertNotEquals(derived.sign("ab3xk91", exp), explicit.sign("ab3xk91", exp),
                "a configured secret must not share the derived key");
        assertTrue(explicit.verify("ab3xk91", exp, explicit.sign("ab3xk91", exp)));
    }

    @Test
    void tamperedSigFailsClosed() {
        ShareTokenSigner signer = new ShareTokenSigner(props("", "it-jwt-secret"));
        long exp = inOneHour();
        String sig = signer.sign("ab3xk91", exp);
        String first = sig.startsWith("0") ? "1" : "0";
        assertFalse(signer.verify("ab3xk91", exp, first + sig.substring(1)));
        assertFalse(signer.verify("ab3xk91", exp, sig + "00"), "length change is not a verify pass");
        assertFalse(signer.verify("ab3xk91", exp, "not-hex-at-all"), "malformed hex fails closed");
        assertFalse(signer.verify("ab3xk91", exp, null));
    }

    @Test
    void expiredExpFailsClosed() {
        ShareTokenSigner signer = new ShareTokenSigner(props("", "it-jwt-secret"));
        long now = System.currentTimeMillis() / 1000;
        assertFalse(signer.verify("ab3xk91", now - 10, signer.sign("ab3xk91", now - 10)),
                "exp strictly in the past is expired");
        assertFalse(signer.verify("ab3xk91", now + 3600, signer.sign("ab3xk91", now + 3600) + "x"));
    }
}
