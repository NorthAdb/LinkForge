package com.linkforge.link;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.entity.MaliciousHost;
import com.linkforge.link.mapper.MaliciousHostMapper;
import com.linkforge.link.service.MaliciousHostSyncService;
import com.linkforge.link.support.MaliciousHostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3b: with the feed switch OFF (the default), ingested rows sit in the
 * table with zero verdict effect — the snapshot is pinned empty and behavior
 * is the pre-3b behavior. This is the posture every other IT context runs in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-malfeed-off:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class MaliciousFeedDisabledIT extends AbstractIntegrationTest {

    @Autowired
    private MaliciousHostSyncService syncService;

    @Autowired
    private MaliciousHostSnapshot snapshot;

    @Autowired
    private MaliciousHostMapper maliciousHostMapper;

    @Test
    void ingestedRowsHaveNoEffectWhileDisabled() throws Exception {
        syncService.ingest("127.0.0.1 off-a.example\n");
        assertTrue(maliciousHostMapper.selectCount(new LambdaQueryWrapper<MaliciousHost>()
                .eq(MaliciousHost::getHost, "off-a.example")) >= 1, "the row is stored");
        assertFalse(snapshot.hosts().contains("off-a.example"),
                "but the verdict snapshot stays empty while the switch is off");

        String token = registerForToken();
        var created = postJson("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://off-a.example/landing")), token);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
    }
}
