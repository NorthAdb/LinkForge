package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * C1 wave B (D-C1-2): HMAC-SHA256 share token over the exact string code + "|" + exp
 * (epoch seconds). A blank linkforge.qr.share-secret derives the key deterministically
 * from the JWT secret (HMAC-SHA256 keyed by the JWT secret over the fixed label) so
 * operators configure nothing. Verify is constant-time and fails closed on expired
 * or malformed input.
 */
@Component
public class ShareTokenSigner {

    private static final String DERIVE_INFO = "linkforge:qr-share:v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public ShareTokenSigner(LinkForgeProperties properties) {
        String secret = properties.getQr().getShareSecret();
        if (secret != null && !secret.isBlank()) {
            this.key = secret.getBytes(StandardCharsets.UTF_8);
            return;
        }
        String jwtSecret = properties.getJwt().getSecret();
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("linkforge.jwt.secret is required to derive the qr share key");
        }
        this.key = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8),
                DERIVE_INFO.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String code, long expEpochSeconds) {
        return HexFormat.of().formatHex(hmac(key, (code + "|" + expEpochSeconds)
                .getBytes(StandardCharsets.UTF_8)));
    }

    /** Expired (exp strictly before now) or malformed sig fails closed. */
    public boolean verify(String code, long expEpochSeconds, String sigHex) {
        if (sigHex == null || expEpochSeconds < nowSeconds()) {
            return false;
        }
        byte[] expected = hmac(key, (code + "|" + expEpochSeconds).getBytes(StandardCharsets.UTF_8));
        try {
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(sigHex));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("hmac-sha256 unavailable", ex);
        }
    }
}
