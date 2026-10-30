package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3: password-protected short links. The owner stores a BCrypt hash; the public GET
 * shows an unlock page (no target leak), HEAD says 401, and unlocking sets a signed
 * cookie that only fits this one link. Red-first: today accessPassword is ignored and
 * the unlock endpoint does not exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PasswordLinkIT extends AbstractIntegrationTest {

    private ResponseEntity<String> postForm(String uri, String form, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(uri, org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }

    private String createProtected(String password) throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of(
                        "originalUrl", "https://example.org/secret-" + unique(""),
                        "accessPassword", password)), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return om.readTree(resp.getBody()).path("data").path("code").asText();
    }

    @Test
    void detailMarksProtectionWithoutLeakingAnything() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of(
                        "originalUrl", "https://example.org/marked-" + unique(""),
                        "accessPassword", "s3cret-pass")), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String code = om.readTree(resp.getBody()).path("data").path("code").asText();
        String body = resp.getBody();
        assertFalse(body.contains("s3cret-pass"), "the plaintext must never come back");
        assertFalse(body.contains("$2"), "no hash may appear in a create response");

        var detail = get("/api/links/" + code, token);
        assertEquals(200, detail.getStatusCode().value(), detail.getBody());
        assertTrue(body(detail).path("data").path("passwordProtected").asBoolean(),
                "the detail must say the link is protected: " + detail.getBody());
        assertFalse(detail.getBody().contains("s3cret-pass"), "no plaintext in the detail");
        assertFalse(detail.getBody().contains("$2"), "no hash in the detail");
    }

    @Test
    void protectedGetShowsUnlockPageWithoutTheTarget() throws Exception {
        String code = createProtected("s3cret-pass");
        var resp = get("/r/" + code, null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        assertHtmlUtf8(resp);
        assertFalse(resp.getBody().contains("example.org"), "the unlock page must not leak the target");
        assertTrue(resp.getBody().contains("password"), "the page asks for a password");
        assertNull(resp.getHeaders().getLocation(), "a locked GET must not redirect");
        var head = head("/r/" + code);
        assertEquals(401, head.getStatusCode().value(), "HEAD of a locked link says 401");
    }

    @Test
    void wrongPasswordGetsTheUnlockPageBack() throws Exception {
        String code = createProtected("s3cret-pass");
        var resp = postForm("/r/" + code + "/unlock", "password=wrong-guess", null);
        assertEquals(401, resp.getStatusCode().value(), resp.getBody());
        assertHtmlUtf8(resp);
        assertTrue(resp.getBody().contains("password"), "a 401 unlock retry shows the form");
    }

    @Test
    void correctPasswordSetsTheCookieAndThenJumps() throws Exception {
        String code = createProtected("s3cret-pass");
        var unlock = postForm("/r/" + code + "/unlock", "password=s3cret-pass", null);
        assertEquals(303, unlock.getStatusCode().value(), unlock.getBody());
        String setCookie = unlock.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "unlocking must set the access cookie");
        assertTrue(setCookie.contains("HttpOnly"), "cookie must be HttpOnly: " + setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), "cookie must be SameSite=Lax");
        assertTrue(setCookie.contains("Path=/r/" + code), "cookie must be scoped to this link");

        String pair = setCookie.split(";", 2)[0];
        var jump = getWithHeader("/r/" + code, null, HttpHeaders.COOKIE, pair);
        assertEquals(302, jump.getStatusCode().value(), "the unlocked cookie buys a real 302");
    }

    @Test
    void passwordLengthIsRejected() throws Exception {
        String token = registerForToken();
        var shortPw = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of(
                        "originalUrl", "https://example.org/pw-short", "accessPassword", "abc")), token);
        assertEquals(400, shortPw.getStatusCode().value(), shortPw.getBody());
        var longPw = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of(
                        "originalUrl", "https://example.org/pw-long", "accessPassword", "x".repeat(65))), token);
        assertEquals(400, longPw.getStatusCode().value(), longPw.getBody());
    }

    @Test
    void setAndClearTogetherIsRejected() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.org/pw-both");
        var resp = patchJson("/api/links/" + code,
                om.writeValueAsString(java.util.Map.of(
                        "accessPassword", "s3cret-pass", "clearAccessPassword", true)), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void rateLimitGuardsBcryptEvenForTheCorrectPassword() throws Exception {
        String code = createProtected("s3cret-pass");
        for (int i = 0; i < 5; i++) {
            var attempt = postForm("/r/" + code + "/unlock", "password=guess-" + i, null);
            assertEquals(401, attempt.getStatusCode().value(), "attempt " + i);
        }
        // The decisive ordering proof: the CORRECT password on the 6th attempt must be
        // rejected by the rate limiter (429), never verified by BCrypt (303).
        var sixth = postForm("/r/" + code + "/unlock", "password=s3cret-pass", null);
        assertEquals(429, sixth.getStatusCode().value(),
                "the rate limit must fire before password verification: " + sixth.getBody());
        String retryAfter = sixth.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 carries Retry-After");
        int seconds = Integer.parseInt(retryAfter.trim());
        assertTrue(seconds >= 1 && seconds <= 300, "Retry-After " + seconds + " outside [1, 300]");
    }

    private static void assertHtmlUtf8(ResponseEntity<String> resp) {
        MediaType ct = resp.getHeaders().getContentType();
        assertNotNull(ct, "HTML gate must declare Content-Type: " + resp.getHeaders());
        assertEquals("text", ct.getType());
        assertEquals("html", ct.getSubtype());
        assertEquals(StandardCharsets.UTF_8, ct.getCharset());
    }
}
