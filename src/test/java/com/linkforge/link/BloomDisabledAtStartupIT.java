package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.support.CodeBloomFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A9 audit N3 + S2 (bloom-off leg): the filter disabled at startup never loads at
 * all — no counter, no rejections, unknown codes 404 through the plain DB path.
 * This context also pins the V4 case-sensitivity contract WITHOUT the Bloom gate:
 * a lower-case variant must miss in the DB itself (utf8mb4_0900_bin, NO PAD).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.bloom.enabled=false",
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-bloomoff:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class BloomDisabledAtStartupIT extends AbstractIntegrationTest {

    @Autowired
    private CodeBloomFilter codeBloom;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void startupDisabledNeverLoadsAndFallsBackToDbPath() throws Exception {
        assertFalse(codeBloom.isReady(), "bloom disabled at startup must never become ready");
        assertNull(meterRegistry.find("linkforge.bloom.loaded").counter(), "loaded must not be registered");
        assertNull(meterRegistry.find("linkforge.bloom.rejected").counter(), "rejected must not be registered");

        assertEquals(404, get("/r/" + unique("nosuch"), null).getStatusCode().value());

        String token = registerForToken();
        String code = createLink(token, "https://example.com/bloomoff-startup");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
    }

    @Test
    void caseVariantMissesInTheDbItselfWhenBloomOff() throws Exception {
        String token = registerForToken();
        String mixed = "Bb" + Long.toString(System.nanoTime(), 36);

        assertEquals(mixed, createCustomLink(token, mixed, "https://example.com/case-off"));

        assertEquals(302, get("/r/" + mixed, null).getStatusCode().value());
        assertEquals(404, get("/r/" + mixed.toLowerCase(), null).getStatusCode().value(),
                "with bloom off the DB collation alone must enforce case sensitivity");
        // NO PAD pin (utf8mb4_0900_bin): a trailing space is a different, missing code
        assertEquals(404, rest.getForEntity(
                java.net.URI.create(rest.getRootUri() + "/r/" + mixed + "%20"), String.class).getStatusCode().value());
    }
}
