package com.linkforge.webhook.support;

import com.linkforge.config.LinkForgeProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSecretCipherTest {

    private static final byte[] KEY_32 = "linkforge-webhook-master-key-32b".getBytes();
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(KEY_32);

    @Test
    void roundTripRecoversThePlaintextAndNeverStoresIt() {
        WebhookSecretCipher cipher = new WebhookSecretCipher(properties(KEY_B64));
        String secret = "whsec_0123456789abcdef";

        String stored = cipher.encrypt(secret);

        assertTrue(stored.startsWith("v1:"), stored);
        assertFalse(stored.contains(secret), "ciphertext must not embed the plaintext");
        assertEquals(secret, cipher.decrypt(stored));
        // GCM: fresh nonce per call → identical plaintexts produce different ciphertexts
        assertNotEquals(stored, cipher.encrypt(secret));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        WebhookSecretCipher cipher = new WebhookSecretCipher(properties(KEY_B64));
        String stored = cipher.encrypt("whsec_0123456789abcdef");
        byte[] packed = Base64.getDecoder().decode(stored.substring(3));
        packed[packed.length - 1] ^= 0x01; // flip one bit of the tag
        String tampered = "v1:" + Base64.getEncoder().encodeToString(packed);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void wrongMasterKeyCannotDecrypt() {
        WebhookSecretCipher cipher = new WebhookSecretCipher(properties(KEY_B64));
        String stored = cipher.encrypt("whsec_0123456789abcdef");
        WebhookSecretCipher other = new WebhookSecretCipher(
                properties(Base64.getEncoder().encodeToString("another-master-key-32-bytes-xxxx".getBytes())));

        assertThrows(IllegalStateException.class, () -> other.decrypt(stored));
    }

    @Test
    void missingOrWrongSizeMasterKeyDegradesOff() {
        assertFalse(new WebhookSecretCipher(properties("")).isAvailable());
        assertFalse(new WebhookSecretCipher(properties("not-base64!!")).isAvailable());
        assertFalse(new WebhookSecretCipher(properties(
                Base64.getEncoder().encodeToString("short".getBytes()))).isAvailable(),
                "a non-32-byte key must not be used");
        assertTrue(new WebhookSecretCipher(properties(KEY_B64)).isAvailable());
        // a degraded cipher fails loudly instead of signing with a broken key
        WebhookSecretCipher degraded = new WebhookSecretCipher(properties(""));
        assertThrows(IllegalStateException.class, () -> degraded.encrypt("whsec_x"));
        assertDoesNotThrow(degraded::isAvailable);
    }

    @Test
    void generatedSecretsLookRight() {
        String secret = WebhookSecretCipher.generateSecret();
        assertTrue(secret.startsWith("whsec_"));
        assertEquals("whsec_".length() + 64, secret.length(), "32 random bytes as hex");
        assertNotEquals(secret, WebhookSecretCipher.generateSecret());
    }

    private LinkForgeProperties properties(String masterKey) {
        LinkForgeProperties properties = new LinkForgeProperties();
        properties.getWebhook().setSecretMasterKey(masterKey);
        return properties;
    }
}
