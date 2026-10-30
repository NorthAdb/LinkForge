package com.linkforge.link.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.dto.QrShareRequest;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.link.support.QrParams;
import com.linkforge.link.support.ShareTokenSigner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * C1 wave B: QR material. The matrix content is ALWAYS {public-base-url}/r/{code} —
 * never the target URL, never any credential (D-C1-3). Share links are short-lived
 * HMAC tokens over code|exp (D-C1-2).
 */
@Service
@RequiredArgsConstructor
public class QrMaterialService {

    private static final int MIN_SHARE_TTL_SECONDS = 1;
    private static final int MAX_SHARE_TTL_SECONDS = 3600;

    private final ShortLinkService shortLinkService;
    private final LinkForgeProperties properties;
    private final ShareTokenSigner shareTokenSigner;

    /** Ownership via getOwnedLink; a valid share signature bypasses it (D-C1-2). */
    public ShortLink resolveForMaterial(String code, boolean shared) {
        return shared ? shortLinkService.requireExisting(code) : shortLinkService.getOwnedLink(code);
    }

    public byte[] render(String code, QrParams params) {
        String content = publicBase() + "/r/" + code;
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE,
                    params.size(), params.size(), Map.of(
                            EncodeHintType.MARGIN, params.margin(),
                            EncodeHintType.ERROR_CORRECTION,
                            ErrorCorrectionLevel.valueOf(String.valueOf(params.ec()))));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return png.toByteArray();
        } catch (WriterException | IOException ex) {
            // bounded short-URL content: only a size/content mismatch lands here
            throw new BizException(400, "qr rendering failed for the requested size");
        }
    }

    /** {public-base-url}/api/links/{code}/qr.png?exp={epoch}&sig={hex}; TTL clamped to [1..3600]. */
    public String shareUrl(String code, QrShareRequest request) {
        int ttl = request == null || request.ttlSeconds() == null
                ? properties.getQr().getShareTtlSeconds()
                : Math.max(MIN_SHARE_TTL_SECONDS,
                        Math.min(MAX_SHARE_TTL_SECONDS, request.ttlSeconds()));
        long exp = Instant.now().getEpochSecond() + ttl;
        return publicBase() + "/api/links/" + code + "/qr.png?exp=" + exp
                + "&sig=" + shareTokenSigner.sign(code, exp);
    }

    private String publicBase() {
        String base = properties.getPublicBaseUrl().trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
