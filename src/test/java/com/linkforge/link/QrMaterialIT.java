package com.linkforge.link;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 wave B (D-C1-1..10): QR material and UTM write-time merge. Pins the observable
 * contract: the QR image is owner-only (or short-lived-signature), encodes only the
 * short link, UTM allowlist merges into original_url without ever touching the
 * redirect path, and a merged URL may not exceed the 2048 column cap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-qrm:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class QrMaterialIT extends AbstractIntegrationTest {

    @Autowired
    private LinkForgeProperties properties;

    @Test
    void qrPatternDecodesToTheShortLinkOnly() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/decode-" + System.currentTimeMillis());

        ResponseEntity<byte[]> png = qrPng("/api/links/" + code + "/qr.png", token);
        assertEquals(200, png.getStatusCode().value(), new String(png.getBody() == null ? new byte[0] : png.getBody()));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png.getBody()));
        assertNotNull(img, "the body must be a real image");
        String decoded = new QRCodeReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))))
                .getText();
        String publicBase = properties.getPublicBaseUrl();
        if (publicBase.endsWith("/")) {
            publicBase = publicBase.substring(0, publicBase.length() - 1);
        }
        assertEquals(publicBase + "/r/" + code, decoded, "D-C1-3: the matrix is exactly the short link");
        assertFalse(decoded.contains("sig="), "no signature material inside the matrix");
        assertFalse(decoded.contains("Bearer"), "no credential inside the matrix");
    }

    @Test
    void ownerDownloadsQrPngWithControllableParams() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/qr-" + System.currentTimeMillis());

        ResponseEntity<byte[]> png = qrPng("/api/links/" + code + "/qr.png", token);
        assertEquals(200, png.getStatusCode().value(), new String(png.getBody() == null ? new byte[0] : png.getBody()));
        assertEquals("image/png", String.valueOf(png.getHeaders().getContentType()),
                "the material endpoint must serve a PNG");
        assertEquals(0x89, png.getBody()[0] & 0xFF, "PNG magic");
        assertEquals(256, pngWidth(png), "default size is 256 (D-C1-4)");

        ResponseEntity<byte[]> sized = qrPng("/api/links/" + code + "/qr.png?size=512&margin=2&ec=H", token);
        assertEquals(200, sized.getStatusCode().value());
        assertEquals(512, pngWidth(sized), "size param must drive the bitmap width");

        assertEquals(400, qrPng("/api/links/" + code + "/qr.png?size=100", token).getStatusCode().value(),
                "size below 128 is a 400 (D-C1-4)");
        assertEquals(400, qrPng("/api/links/" + code + "/qr.png?size=2048", token).getStatusCode().value(),
                "size above 1024 is a 400");
        assertEquals(400, qrPng("/api/links/" + code + "/qr.png?margin=9", token).getStatusCode().value(),
                "margin above 4 is a 400");
        assertEquals(400, qrPng("/api/links/" + code + "/qr.png?ec=X", token).getStatusCode().value(),
                "unknown ec level is a 400");
    }

    @Test
    void anonymousWithoutSignatureGets401AndNonOwnerGets404() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/qr-guard-" + System.currentTimeMillis());

        assertEquals(401, qrPng("/api/links/" + code + "/qr.png", null).getStatusCode().value(),
                "no credentials and no signature must be a 401");
        assertEquals(401, qrPng("/api/links/" + code + "/qr.png?exp=1&sig=deadbeef", null).getStatusCode().value(),
                "a bogus signature must not open the route");
        assertEquals(404, qrPng("/api/links/" + code + "/qr.png", registerForToken())
                .getStatusCode().value(), "another owner must see 404 (no existence leak)");
    }

    @Test
    void shareSignatureDownloadsThePngWithoutAnyCredentials() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/qr-share-" + System.currentTimeMillis());

        ResponseEntity<String> share = postJson("/api/links/" + code + "/qr-share", "{}", token);
        assertEquals(200, share.getStatusCode().value(), share.getBody());
        String url = body(share).path("data").path("url").asText();
        assertTrue(url.contains("/qr.png?") && url.contains("exp=") && url.contains("sig="),
                "share url must carry exp and sig: " + url);

        URI shareUri = URI.create(url);
        String localPath = shareUri.getRawPath() + "?" + shareUri.getRawQuery();
        ResponseEntity<byte[]> anonymous = qrPng(localPath, null);
        assertEquals(200, anonymous.getStatusCode().value(),
                "a valid signature must download without any credential");
        assertEquals("image/png", String.valueOf(anonymous.getHeaders().getContentType()));
        assertEquals(256, pngWidth(anonymous));

        String tamperedSig = localPath.replaceAll("(sig=)[0-9a-fA-F]", "$10");
        assertEquals(401, qrPng(tamperedSig, null).getStatusCode().value(), "tampered sig must be a 401");
        String tamperedExp = localPath.replaceAll("(exp=)\\d+", "$10");
        assertEquals(401, qrPng(tamperedExp, null).getStatusCode().value(), "tampered exp must be a 401");
    }

    @Test
    void passwordProtectedLinkQrStillGoesThroughUnlock() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/locked-" + System.currentTimeMillis(),
                "accessPassword", "pass-1234")), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String code = body(resp).path("data").path("code").asText();

        assertEquals(200, qrPng("/api/links/" + code + "/qr.png", token).getStatusCode().value(),
                "the owner can still mint material for a password link (D-C1-9)");
        var redirect = get("/r/" + code, null);
        assertEquals(200, redirect.getStatusCode().value(),
                "scanning the code must hit the unlock gate, never the 302 (C1-5)");
    }

    @Test
    void utmParamsMergeIntoOriginalUrlOnCreate() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://target.example.com/landing",
                "utmSource", "news",
                "utmMedium", "cpc",
                "utmCampaign", "launch",
                "utmTerm", "kw",
                "utmContent", "banner-a")), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String code = body(resp).path("data").path("code").asText();

        var detail = body(get("/api/links/" + code, token)).path("data");
        assertEquals("https://target.example.com/landing?utm_source=news&utm_medium=cpc"
                        + "&utm_campaign=launch&utm_term=kw&utm_content=banner-a",
                detail.path("originalUrl").asText(),
                "D-C1-6/7: allowlisted utm keys merge into the stored URL in canonical order");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value(),
                "the redirect path itself is untouched (D-C1-31)");
    }

    @Test
    void unknownAttributionKeysAndBadCharsetAreRejected() throws Exception {
        String token = registerForToken();

        var gclid = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://target.example.com/a", "utmSource", "ok", "gclid", "x")), token);
        assertEquals(400, gclid.getStatusCode().value(), "unknown click-attribution keys are a 400: "
                + gclid.getBody());

        var unknownUtm = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://target.example.com/a", "utm_id", "9")), token);
        assertEquals(400, unknownUtm.getStatusCode().value(), "unknown utm* keys are a 400: "
                + unknownUtm.getBody());

        var charset = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://target.example.com/a", "utmSource", "bad value!")), token);
        assertEquals(400, charset.getStatusCode().value(), "values outside [A-Za-z0-9._-] are a 400: "
                + charset.getBody());

        var tooLong = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://target.example.com/a",
                "utmSource", "a".repeat(65))), token);
        assertEquals(400, tooLong.getStatusCode().value(), "values above 64 chars are a 400: "
                + tooLong.getBody());

        var ok = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://target.example.com/a",
                "utmSource", "_-." + "a".repeat(61))), token);
        assertEquals(200, ok.getStatusCode().value(), "64 chars of the allowed alphabet pass: "
                + ok.getBody());
    }

    @Test
    void existingQueryParamsAreNeverOverwritten() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://t.example.com/x?utm_source=keep",
                "utmSource", "fresh",
                "utmCampaign", "spring")), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String code = body(resp).path("data").path("code").asText();

        String stored = body(get("/api/links/" + code, token)).path("data").path("originalUrl").asText();
        assertEquals("https://t.example.com/x?utm_source=keep&utm_campaign=spring", stored,
                "D-C1-7: an existing utm_source wins; only the missing key is appended");
    }

    @Test
    void utmMergesOnPatchAndRespectsTheColumnCap() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://target.example.com/p");
        var patched = patchJson("/api/links/" + code, om.writeValueAsString(Map.of(
                "utmMedium", "email")), token);
        assertEquals(200, patched.getStatusCode().value(), patched.getBody());
        assertEquals("https://target.example.com/p?utm_medium=email",
                body(get("/api/links/" + code, token)).path("data").path("originalUrl").asText(),
                "utm fields alone are a valid update (D-C1-6)");

        String longUrl = "https://t.example.com/" + "a".repeat(2020);
        var within = postJson("/api/links", om.writeValueAsString(Map.of("originalUrl", longUrl)), token);
        assertEquals(200, within.getStatusCode().value(), "2042 chars alone fit the column: "
                + within.getBody());

        var overflow = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", longUrl, "utmSource", "abcdefgh")), token);
        assertEquals(400, overflow.getStatusCode().value(),
                "a merge pushing past 2048 must be a 400, not a DB error: " + overflow.getBody());
    }

    private ResponseEntity<byte[]> qrPng(String uri, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private static int pngWidth(ResponseEntity<byte[]> png) {
        byte[] b = png.getBody();
        assertNotNull(b, "PNG body expected");
        return ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
    }
}
