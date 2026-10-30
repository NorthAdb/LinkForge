package com.linkforge.retention;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.stats.mapper.OutboxMapper;
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SentRowCleanupTest {

    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private WebhookDeliveryMapper deliveryMapper;

    private LinkForgeProperties properties;
    private SentRowCleanup cleanup;

    @BeforeEach
    void setUp() {
        properties = new LinkForgeProperties();
        cleanup = new SentRowCleanup(outboxMapper, deliveryMapper, properties);
    }

    @Test
    void nonPositiveRetentionDeletesNothing() {
        properties.getRetention().setSentDays(0);
        assertEquals(0, cleanup.purgeSent());
        properties.getRetention().setSentDays(-3);
        assertEquals(0, cleanup.purgeSent());
        verifyNoInteractions(outboxMapper, deliveryMapper);
    }

    @Test
    void deletesOnlyABoundedSentBatchAndStopsWhenCaughtUp() {
        when(outboxMapper.deleteSentOlderThan(anyLong(), anyInt())).thenReturn(4);
        when(deliveryMapper.deleteSentOlderThan(any(), anyInt())).thenReturn(1);

        assertEquals(5, cleanup.purgeSent());

        verify(outboxMapper).deleteSentOlderThan(eq(7L * 86_400L), eq(1000));
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deliveryMapper).deleteSentOlderThan(cutoff.capture(), eq(1000));
        LocalDateTime expected = LocalDateTime.now().minusDays(7);
        assertTrue(Duration.between(cutoff.getValue(), expected).abs().getSeconds() < 5,
                cutoff.getValue().toString());
    }

    @Test
    void fullBatchesContinueUntilTheCapThenALaterPassIsIdle() {
        properties.getRetention().setCleanupBatchSize(10);
        properties.getRetention().setCleanupMaxPasses(3);
        when(outboxMapper.deleteSentOlderThan(anyLong(), anyInt())).thenReturn(10, 10, 10, 0);
        when(deliveryMapper.deleteSentOlderThan(any(), anyInt())).thenReturn(10, 10, 10, 0);

        assertEquals(60, cleanup.purgeSent());
        verify(outboxMapper, times(3)).deleteSentOlderThan(anyLong(), eq(10));

        when(outboxMapper.deleteSentOlderThan(anyLong(), anyInt())).thenReturn(0);
        when(deliveryMapper.deleteSentOlderThan(any(), anyInt())).thenReturn(0);
        assertEquals(0, cleanup.purgeSent());
        verify(outboxMapper, never()).deleteSentOlderThan(eq(0L), anyInt());
    }

    @Test
    void deleteStatementsTouchOnlySentRows() throws Exception {
        String outbox = sql(OutboxMapper.class, "deleteSentOlderThan", long.class, int.class);
        String webhook = sql(WebhookDeliveryMapper.class, "deleteSentOlderThan", LocalDateTime.class, int.class);
        for (String statement : new String[] {outbox, webhook}) {
            String folded = statement.toUpperCase();
            assertTrue(folded.contains("STATUS = 'SENT'"), statement);
            assertTrue(folded.contains("SENT_AT IS NOT NULL"), statement);
            assertTrue(folded.contains("LIMIT"), statement);
            assertFalse(folded.contains("PENDING"), statement);
            assertFalse(folded.contains("FAILED"), statement);
            assertFalse(folded.contains("STATUS <>") || folded.contains("STATUS !="), statement);
        }
        assertTrue(outbox.toUpperCase().contains("DATE_SUB(NOW()"), outbox);
        assertTrue(webhook.contains("#{cutoff}"), webhook);
        assertFalse(webhook.toUpperCase().contains("NOW()"), webhook);
    }

    private static String sql(Class<?> type, String name, Class<?>... params) throws Exception {
        Delete delete = type.getMethod(name, params).getAnnotation(Delete.class);
        assertNotNull(delete, name);
        return String.join(" ", delete.value());
    }
}
