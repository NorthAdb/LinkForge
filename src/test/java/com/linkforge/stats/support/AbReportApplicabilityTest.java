package com.linkforge.stats.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S/N wave (audit S1): the applicability gate as a pure function — precedence
 * and boundary behavior, without a database. The HTTP-level contract lives
 * in AbReportIT.
 */
class AbReportApplicabilityTest {

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 1, 8, 0, 0);
    private static final LocalDateTime BORN_LONG_AGO = LocalDateTime.of(2025, 12, 1, 0, 0);

    @Test
    void conditionalRoutingWinsOverEveryOtherSignal() {
        // Even a provably changed configuration reports CONDITIONAL first:
        // the candidate-set argument invalidates the fit regardless of history.
        assertEquals(AbReport.NotApplicableReason.CONDITIONAL_ROUTING,
                AbReport.checkApplicability(true, true,
                        WINDOW_START.plusDays(1), BORN_LONG_AGO, WINDOW_START));
        assertEquals(AbReport.NotApplicableReason.CONDITIONAL_ROUTING,
                AbReport.checkApplicability(true, false,
                        WINDOW_START.minusDays(30), BORN_LONG_AGO, WINDOW_START));
    }

    @Test
    void orphansProveAChangeEvenForAnOldInstall() {
        assertEquals(AbReport.NotApplicableReason.CONFIG_CHANGED_IN_WINDOW,
                AbReport.checkApplicability(false, true,
                        WINDOW_START.minusDays(30), BORN_LONG_AGO, WINDOW_START));
    }

    @Test
    void replacementInsideTheWindowIsAChange() {
        // Installed inside the window, clearly after the link's birth.
        assertEquals(AbReport.NotApplicableReason.CONFIG_CHANGED_IN_WINDOW,
                AbReport.checkApplicability(false, false,
                        WINDOW_START.plusHours(3), BORN_LONG_AGO, WINDOW_START));
    }

    @Test
    void birthInstallInsideTheWindowIsUnknownNotChanged() {
        LocalDateTime born = WINDOW_START.plusHours(3);
        // Install stamp indistinguishable from the birth install.
        assertEquals(AbReport.NotApplicableReason.CONFIG_HISTORY_UNKNOWN,
                AbReport.checkApplicability(false, false, born, born, WINDOW_START));
        // Exactly at the 2s tolerance: still not provably a replacement.
        assertEquals(AbReport.NotApplicableReason.CONFIG_HISTORY_UNKNOWN,
                AbReport.checkApplicability(false, false, born.plusSeconds(2), born, WINDOW_START));
        // One second past tolerance: provably a replacement.
        assertEquals(AbReport.NotApplicableReason.CONFIG_CHANGED_IN_WINDOW,
                AbReport.checkApplicability(false, false, born.plusSeconds(3), born, WINDOW_START));
        // No birth reference at all: unprovable either way.
        assertEquals(AbReport.NotApplicableReason.CONFIG_HISTORY_UNKNOWN,
                AbReport.checkApplicability(false, false, born, null, WINDOW_START));
    }

    @Test
    void installPredatingTheWindowIsProvenStable() {
        assertNull(AbReport.checkApplicability(false, false,
                WINDOW_START.minusSeconds(1), BORN_LONG_AGO, WINDOW_START));
        // Boundary: installed in the window's first second is NOT proven
        // (DATETIME truncates to seconds) — it falls to the unknown branch
        // when it matches the birth install.
        LocalDateTime bornAtStart = WINDOW_START;
        assertEquals(AbReport.NotApplicableReason.CONFIG_HISTORY_UNKNOWN,
                AbReport.checkApplicability(false, false, bornAtStart, bornAtStart, WINDOW_START));
    }

    @Test
    void missingInstallStampProvesNothing() {
        assertEquals(AbReport.NotApplicableReason.CONFIG_HISTORY_UNKNOWN,
                AbReport.checkApplicability(false, false, null, BORN_LONG_AGO, WINDOW_START));
    }

    @Test
    void notApplicableResultNullsEveryFitNumber() {
        AbReport.Result result = AbReport.notApplicable(
                List.of(new AbReport.VariantInput("A", 1, 40),
                        new AbReport.VariantInput("B", 3, 60)),
                List.of(new AbReport.VariantInput("C", 0, 7)),
                AbReport.NotApplicableReason.CONDITIONAL_ROUTING);
        assertEquals(AbReport.Verdict.NOT_APPLICABLE, result.verdict());
        assertEquals(AbReport.NotApplicableReason.CONDITIONAL_ROUTING, result.reason());
        assertEquals(100, result.totalClicks());
        assertNull(result.chiSquare());
        assertNull(result.pValue());
        assertEquals(0, result.degreesOfFreedom());
        AbReport.Row a = result.rows().get(0);
        assertEquals(1, a.weight());
        assertEquals(40, a.observed());
        assertNull(a.expected());
        assertNull(a.share());
        assertNull(a.ciLow());
        assertNull(a.ciHigh());
        assertTrue(result.rows().get(2).orphaned());
    }

    @Test
    void structuralNotApplicableCarriesItsReason() {
        AbReport.Result result = AbReport.compute(
                List.of(new AbReport.VariantInput("A", 1, 100)), List.of());
        assertEquals(AbReport.Verdict.NOT_APPLICABLE, result.verdict());
        assertEquals(AbReport.NotApplicableReason.TOO_FEW_VARIANTS, result.reason());
    }
}
