package com.linkforge.webhook.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Pure window arithmetic for AGGREGATE webhook delivery (D-C1-18..24). Business
 * data (a click's clickedAt) lives on the JVM clock, so window classification is
 * computed from {@link ZoneId#systemDefault()} epoch seconds; extraction as a
 * static helper keeps it unit-testable without Spring or a DB.
 */
public final class AggregateWindows {

    /** Null-safe default when an endpoint row predates the column or stores NULL. */
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    private AggregateWindows() {
    }

    /** Where a click landed: its own window, or the open one when late (D-C1-24). */
    public record Slot(long windowStart, boolean late) {
    }

    /** Floor of the click's epoch second onto the window grid (null clickedAt = now). */
    public static long windowStartOf(LocalDateTime clickedAt, Integer windowSeconds) {
        int window = window(windowSeconds);
        long epoch = (clickedAt != null ? clickedAt : LocalDateTime.now())
                .atZone(ZoneId.systemDefault()).toEpochSecond();
        return Math.floorDiv(epoch, window) * (long) window;
    }

    /** The window currently being filled, on the same JVM-clock grid. */
    public static long openWindowStart(Integer windowSeconds, long nowEpochSeconds) {
        int window = window(windowSeconds);
        return Math.floorDiv(nowEpochSeconds, window) * (long) window;
    }

    /** A click is late when its own window closed before it arrived. */
    public static boolean isLate(long windowStart, long openWindowStart) {
        return windowStart < openWindowStart;
    }

    /** Classification in one step: late clicks join the open window (D-C1-24). */
    public static Slot slotOf(LocalDateTime clickedAt, Integer windowSeconds, long nowEpochSeconds) {
        long open = openWindowStart(windowSeconds, nowEpochSeconds);
        long own = windowStartOf(clickedAt, windowSeconds);
        boolean late = isLate(own, open);
        return new Slot(late ? open : own, late);
    }

    private static int window(Integer windowSeconds) {
        return windowSeconds != null && windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
    }
}
