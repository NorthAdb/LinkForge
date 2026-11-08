package com.linkforge.security;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.apikey.service.ApiKeyService;
import com.linkforge.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Audit S4-5 contract: when the auth store itself is down, the auth filters
 * answer a stable 503 ("authentication store unavailable") instead of
 * collapsing the failure into "unauthenticated" — which is indistinguishable
 * from a wrong credential and sent clients into re-login loops during a DB
 * outage. Failure injection replaces the two store seams with mocks that
 * throw a data-access exception carrying a marker; the marker must never
 * reach the wire, and no request may be authenticated by the failure.
 *
 * <p>The JWT is minted directly for a synthetic user id: registration would
 * need the (mocked) account mapper, and the filter dies at the recheck
 * before anything else could need a real row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-authdep:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class AuthDependencyFailureIT extends AbstractIntegrationTest {

    private static final String MARKER = "auth-store-marker-91e7c3";

    @MockBean
    private UserAccountMapper userAccountMapper;

    @MockBean
    private ApiKeyService apiKeyService;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void failTheStore() {
        when(userAccountMapper.selectById(anyLong()))
                .thenThrow(new DataAccessResourceFailureException("injected failure " + MARKER));
        when(apiKeyService.authenticate(anyString()))
                .thenThrow(new DataAccessResourceFailureException("injected failure " + MARKER));
    }

    private void assertUnavailable(ResponseEntity<String> resp) {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode(), resp.getBody());
        String rawBody = resp.getBody();
        assertNotNull(rawBody);
        assertTrue(rawBody.contains("\"code\":503"), rawBody);
        assertTrue(rawBody.contains("authentication store unavailable"), rawBody);
        assertFalse(rawBody.contains(MARKER), "store detail must never reach the wire");
    }

    @Test
    void jwtStoreFailureIsUnavailableNotAuthorized() throws Exception {
        String token = jwtService.createToken(424_242L, 1L, "store-victim", 0);

        ResponseEntity<String> resp = get("/api/links", token);

        // 503, not 200 (never authenticated) and not 401 (not a bad credential).
        assertUnavailable(resp);
    }

    @Test
    void apiKeyStoreFailureIsUnavailableNotAuthorized() {
        ResponseEntity<String> resp = getWithHeader("/api/links", null,
                "X-API-Key", "lfk_store_failure_probe");

        assertUnavailable(resp);
    }

    @Test
    void requestsWithoutCredentialsAreUnaffected() {
        // No credential → the recheck is never reached, store state is
        // irrelevant: the ordinary 401 stands, no 503 leaks into it.
        ResponseEntity<String> anon = get("/api/links", null);
        assertEquals(HttpStatus.UNAUTHORIZED, anon.getStatusCode(), anon.getBody());

        // And permit-all traffic keeps flowing while the auth store is down.
        ResponseEntity<String> health = get("/actuator/health", null);
        assertEquals(HttpStatus.OK, health.getStatusCode(), health.getBody());
    }
}
