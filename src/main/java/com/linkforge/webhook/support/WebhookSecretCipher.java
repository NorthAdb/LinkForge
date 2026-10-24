package com.linkforge.webhook.support;

import com.linkforge.config.LinkForgeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * B-wave2 D-Bw2-11 (Q1, decided): webhook secrets are signed at send time, so the
 * plaintext must be recoverable — a hash is impossible. At rest the secret is
 * AES-256-GCM ciphertext, encoded "v1:" + Base64(nonce(12) || ciphertext||tag).
 * The master key comes from LINKFORGE_WEBHOOK_SECRET_KEY (Base64, exactly 32
 * bytes) and is never persisted. Without a usable key the whole webhook feature
 * degrades off (endpoints cannot be saved, deliveries cannot be signed) — an
 * explicit, logged fail-safe rather than silently signing with a broken key.
 * Losing the master key makes every stored secret unrecoverable; the operator
 * must rotate all tenant secrets (README).
 */
@Slf4j
@Component
public class WebhookSecretCipher {

    private static final String VERSION_PREFIX = "v1:";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final byte[] masterKey;

    public WebhookSecretCipher(LinkForgeProperties properties) {
        byte[] key = null;
        String configured = properties.getWebhook().getSecretMasterKey();
        if (configured != null && !configured.isBlank()) {
            try {
                key = Base64.getDecoder().decode(configured.trim());
            } catch (IllegalArgumentException ex) {
                log.warn("LINKFORGE_WEBHOOK_SECRET_KEY is not valid Base64 — webhook disabled");
                key = null;
            }
            if (key != null && key.length != 32) {
                log.warn("LINKFORGE_WEBHOOK_SECRET_KEY must decode to 32 bytes (AES-256), got {} — webhook disabled",
                        key.length);
                key = null;
            }
        }
        this.masterKey = key;
        if (key == null) {
            log.warn("webhook master key unavailable: endpoint management and delivery signing are OFF");
        }
    }

    public boolean isAvailable() {
        return masterKey != null;
    }

    public String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"));
            byte[] nonce = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) {
            throw new IllegalStateException("webhook secret encryption failed", ex);
        }
    }

    /** @throws IllegalStateException on a wrong master key or tampered ciphertext. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("webhook secret is not in v1: ciphertext form");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("webhook secret decryption failed (wrong master key or tampered data)", ex);
        }
    }

    public static String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder("whsec_");
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
