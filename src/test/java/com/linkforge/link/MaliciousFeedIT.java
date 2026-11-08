package com.linkforge.link;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.entity.MaliciousHost;
import com.linkforge.link.mapper.MaliciousHostMapper;
import com.linkforge.link.service.MaliciousHostSyncService;
import com.linkforge.link.support.PhishingPolicy;
import com.linkforge.link.support.TargetPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3b: the malicious-host feed, switched ON for this context. Ingest is
 * driven directly with fixture text (fetch never runs in tests — the
 * scheduler's initial delay is pushed an hour out so it cannot fire either).
 * Host names are unique per test: the table is shared with every other IT
 * context in the JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-malfeed:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.malicious-feed.enabled=true",
        "linkforge.malicious-feed.initial-delay-ms=3600000",
        "linkforge.phishing.block-domains=local-listed.example",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class MaliciousFeedIT extends AbstractIntegrationTest {

    @Autowired
    private MaliciousHostSyncService syncService;

    @Autowired
    private PhishingPolicy phishingPolicy;

    @Autowired
    private MaliciousHostMapper maliciousHostMapper;

    private long rowsFor(String... hosts) {
        return maliciousHostMapper.selectCount(new LambdaQueryWrapper<MaliciousHost>()
                .in(MaliciousHost::getHost, List.of(hosts)));
    }

    @Test
    void ingestedFeedHostBlocksCreateWithFeedReason() throws Exception {
        int ingested = syncService.ingest(String.join("\n",
                "# URLhaus hostfile (fixture)",
                "127.0.0.1\tfeed-a1.example",
                "127.0.0.1 localhost",
                "plain-b1.example",
                "https://url-c1.example/some/path"));
        assertEquals(3, ingested);

        String token = registerForToken();
        var blocked = postJson("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://feed-a1.example/landing")), token);
        assertEquals(400, blocked.getStatusCode().value(), blocked.getBody());

        TargetPolicy.Verdict verdict = phishingPolicy.evaluate("https://feed-a1.example/landing", true);
        assertEquals(TargetPolicy.Action.BLOCK, verdict.action());
        assertEquals(TargetPolicy.Reason.MALICIOUS_FEED, verdict.reason());
        assertEquals("malicious_feed", verdict.reason().tag());
        // suffix rule is the local lists' rule: subdomains are covered too
        assertEquals(TargetPolicy.Reason.MALICIOUS_FEED,
                phishingPolicy.evaluate("https://sub.feed-a1.example/", true).reason());
        // the other fixture shapes landed as well
        assertEquals(TargetPolicy.Reason.MALICIOUS_FEED,
                phishingPolicy.evaluate("https://plain-b1.example/", true).reason());
        assertEquals(TargetPolicy.Reason.MALICIOUS_FEED,
                phishingPolicy.evaluate("https://url-c1.example/", true).reason());
    }

    @Test
    void ingestIsIdempotentAndVanishedHostsAreNotDeleted() {
        String fixture = "127.0.0.1 keep-a2.example\n127.0.0.1 keep-b2.example\n";
        syncService.ingest(fixture);
        assertEquals(2, rowsFor("keep-a2.example", "keep-b2.example"));
        syncService.ingest(fixture);
        assertEquals(2, rowsFor("keep-a2.example", "keep-b2.example"), "re-ingest must not duplicate rows");
        syncService.ingest("127.0.0.1 keep-a2.example\n");
        assertEquals(2, rowsFor("keep-a2.example", "keep-b2.example"),
                "a host that leaves the feed keeps its row (no automatic delisting)");
    }

    @Test
    void localBlockListKeepsItsAttributionAndUnionsWithFeed() throws Exception {
        TargetPolicy.Verdict local = phishingPolicy.evaluate("https://local-listed.example/", true);
        assertEquals(TargetPolicy.Action.BLOCK, local.action());
        assertEquals(TargetPolicy.Reason.BLOCK_LIST, local.reason());

        // a host on BOTH lists still reports the local reason
        syncService.ingest("127.0.0.1 local-listed.example\n");
        assertEquals(TargetPolicy.Reason.BLOCK_LIST,
                phishingPolicy.evaluate("https://local-listed.example/", true).reason());

        String token = registerForToken();
        var blocked = postJson("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://local-listed.example/x")), token);
        assertEquals(400, blocked.getStatusCode().value(), blocked.getBody());
    }

    @Test
    void updateToAFeedHostIsRejected() throws Exception {
        syncService.ingest("127.0.0.1 feed-a4.example\n");
        String token = registerForToken();
        String code = createLink(token, "https://good.example/" + unique(""));
        var patch = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("originalUrl", "https://feed-a4.example/evil")), token);
        assertEquals(400, patch.getStatusCode().value(), patch.getBody());
        assertTrue(body(patch).path("code").asInt() == 400);
    }
}
