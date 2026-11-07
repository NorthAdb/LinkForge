package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.entity.Outbox;
import com.linkforge.stats.mapper.OutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T-M3: the outbox mark* updates are status CAS, the same standard the
 * webhook delivery mapper already followed. A row another relay instance
 * already moved out of PENDING must not be flipped again (a SENT row turned
 * FAILED would be manually replayed and delivered twice).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OutboxStatusGuardIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxMapper outboxMapper;

    private final List<Long> insertedIds = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        for (Long id : insertedIds) {
            outboxMapper.deleteById(id);
        }
    }

    private Outbox insertPending() {
        Outbox row = new Outbox();
        row.setEventId("guard-it-" + System.nanoTime());
        row.setPayload("{}");
        row.setStatus(Outbox.STATUS_PENDING);
        row.setAttempts(0);
        // far-future schedule: the running relay must never pick these rows up
        row.setNextAttemptAt(LocalDateTime.now().plusDays(1));
        row.setCreatedAt(LocalDateTime.now());
        outboxMapper.insert(row);
        insertedIds.add(row.getId());
        return row;
    }

    @Test
    void sentRowCannotBeFlippedByLaterMarks() {
        Outbox row = insertPending();

        assertEquals(1, outboxMapper.markSent(row.getId()), "PENDING -> SENT applies");
        assertEquals(0, outboxMapper.markSent(row.getId()), "already SENT: the guard rejects a repeat");
        assertEquals(0, outboxMapper.markFailed(row.getId()), "SENT must not flip to FAILED");
        assertEquals(0, outboxMapper.markRetry(row.getId(), 30), "SENT must not be rescheduled");

        Outbox after = outboxMapper.selectById(row.getId());
        assertEquals(Outbox.STATUS_SENT, after.getStatus());
        assertEquals(0, after.getAttempts(), "the rejected retry must not burn an attempt");
        assertNotNull(after.getSentAt());
    }

    @Test
    void pendingRowStillRetriesAndFailsNormally() {
        Outbox row = insertPending();

        assertEquals(1, outboxMapper.markRetry(row.getId(), 30));
        Outbox retried = outboxMapper.selectById(row.getId());
        assertEquals(Outbox.STATUS_PENDING, retried.getStatus());
        assertEquals(1, retried.getAttempts());

        assertEquals(1, outboxMapper.markFailed(row.getId()));
        assertEquals(Outbox.STATUS_FAILED, outboxMapper.selectById(row.getId()).getStatus());
        assertEquals(0, outboxMapper.markSent(row.getId()), "FAILED is terminal for the relay marks");
    }
}
