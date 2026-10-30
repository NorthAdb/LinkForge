package com.linkforge.webhook.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure arithmetic of the AGGREGATE window grid (D-C1-19/24): flooring, the 60s
 * null-safe default, and the late rule "older than the open window joins the open
 * window flagged late".
 */
class AggregateWindowsTest {

    private static LocalDateTime atEpoch(long epochSeconds) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
    }

    @Test
    void windowStartFloorsOntoTheWindowGrid() {
        assertEquals(945, AggregateWindows.windowStartOf(atEpoch(955), 15));
        assertEquals(990, AggregateWindows.windowStartOf(atEpoch(1000), 15));
        assertEquals(960, AggregateWindows.windowStartOf(atEpoch(1000), null), "null window falls back to 60s");
        assertEquals(0, AggregateWindows.windowStartOf(atEpoch(59), 60));
    }

    @Test
    void openWindowStartUsesTheSameGrid() {
        assertEquals(990, AggregateWindows.openWindowStart(15, 1000));
        assertEquals(960, AggregateWindows.openWindowStart(null, 1000));
    }

    @Test
    void clickOlderThanTheOpenWindowIsLateAndJoinsIt() {
        // 955 floors to 945, the open window at now=1000 is 990 -> late into 990
        AggregateWindows.Slot slot = AggregateWindows.slotOf(atEpoch(955), 15, 1000);
        assertEquals(990, slot.windowStart());
        assertTrue(slot.late());
    }

    @Test
    void clickInsideTheOpenWindowIsOnTime() {
        assertEquals(new AggregateWindows.Slot(990, false), AggregateWindows.slotOf(atEpoch(1000), 15, 1000));
    }

    @Test
    void clickExactlyAtTheOpenWindowStartIsNotLate() {
        assertEquals(new AggregateWindows.Slot(990, false), AggregateWindows.slotOf(atEpoch(990), 15, 1000));
    }

    @Test
    void oneSecondOlderThanTheOpenWindowStartIsAlreadyLate() {
        assertEquals(new AggregateWindows.Slot(990, true), AggregateWindows.slotOf(atEpoch(989), 15, 1000));
    }

    @Test
    void nullWindowUsesTheSixtySecondDefault() {
        assertEquals(new AggregateWindows.Slot(960, false), AggregateWindows.slotOf(atEpoch(1000), null, 1000));
        assertEquals(new AggregateWindows.Slot(960, true), AggregateWindows.slotOf(atEpoch(959), null, 1000));
    }

    @Test
    void nullClickedAtIsTreatedAsNow() {
        long before = System.currentTimeMillis() / 1000;
        long windowStart = AggregateWindows.windowStartOf(null, 15);
        long after = System.currentTimeMillis() / 1000;
        assertTrue(windowStart >= AggregateWindows.openWindowStart(15, before),
                "must not precede the window open at 'before'");
        assertTrue(windowStart <= AggregateWindows.openWindowStart(15, after),
                "must not follow the window open at 'after'");
    }
}
