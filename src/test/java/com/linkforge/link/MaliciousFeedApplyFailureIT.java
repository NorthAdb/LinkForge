package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.mapper.MaliciousHostMapper;
import com.linkforge.link.service.MaliciousHostSyncService;
import com.linkforge.link.support.MaliciousHostSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Audit S3 (the one kept cut): last_success means "the last feed that was
 * fully APPLIED", not "the last feed that was downloaded". Here the upsert
 * succeeds but the snapshot re-read fails, so the new feed never reaches
 * the in-memory verdicts: the ingest must count result=apply_failed, leave
 * last_success untouched, and keep serving the previous snapshot. The feed
 * mapper is the injection seam (mocked only in this class's context — the
 * production MaliciousFeedIT keeps the real mapper over HTTP-shaped
 * fixtures).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-feedapply:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "linkforge.malicious-feed.enabled=true",
        "linkforge.malicious-feed.urlhaus-auth-key=it-key"
})
class MaliciousFeedApplyFailureIT extends AbstractIntegrationTest {

    @MockBean
    private MaliciousHostMapper hostMapper;

    @Autowired
    private MaliciousHostSyncService syncService;

    @Autowired
    private MaliciousHostSnapshot snapshot;

    @Autowired
    private MeterRegistry meterRegistry;

    /** Same hosts-file shape MaliciousFeedIT ingests: two feed hosts. */
    private static String fixture() {
        return "127.0.0.1\tnew-feed.example\nplain-feed.example\n";
    }

    private double lastSuccess() {
        var gauge = meterRegistry.find("linkforge.malicious.feed.last_success").gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    private double syncCount(String result) {
        var counter = meterRegistry.find("linkforge.malicious.feed.sync")
                .tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void snapshotApplyFailureIsNotSuccess() throws Exception {
        // A known-good snapshot is live and verdicating.
        when(hostMapper.selectAllHosts()).thenReturn(List.of("known-bad.example"));
        when(hostMapper.upsertBatch(anyList(), anyString(), any(java.time.LocalDateTime.class))).thenReturn(2);
        assertTrue(snapshot.refresh(), "baseline snapshot applies");
        assertTrue(snapshot.hosts().contains("known-bad.example"));

        // One fully applied ingest: last_success takes a real value.
        syncService.ingest(fixture());
        double appliedAt = lastSuccess();
        assertTrue(appliedAt > 0, "a successful apply stamps last_success");
        assertEquals(1.0, syncCount("ok"));

        // The next feed downloads and upserts fine, but the snapshot re-read
        // (the apply step) breaks.
        when(hostMapper.selectAllHosts())
                .thenThrow(new DataAccessResourceFailureException("snapshot read failed"));
        syncService.ingest(fixture());

        assertEquals(appliedAt, lastSuccess(), 0.0,
                "last_success must not move when the feed was never applied");
        assertEquals(1.0, syncCount("apply_failed"));
        assertEquals(1.0, syncCount("ok"), "no second success may be counted");
        assertTrue(snapshot.hosts().contains("known-bad.example"),
                "the previous snapshot keeps verdicating throughout");
    }
}
