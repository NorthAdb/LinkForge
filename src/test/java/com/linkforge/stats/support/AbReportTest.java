package com.linkforge.stats.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3b: pins the fit math against known values — chi-square critical
 * values from standard tables (df=1: 3.841 @ .05, 10.828 @ .001; df=2: 5.991;
 * df=3: 7.815; df=10: 18.307) and the textbook Wilson interval for 50/100.
 */
class AbReportTest {

    private static AbReport.VariantInput v(String key, int weight, long observed) {
        return new AbReport.VariantInput(key, weight, observed);
    }

    @Test
    void chiSquarePValueMatchesKnownCriticalValues() {
        assertEquals(1.0, AbReport.chiSquarePValue(1, 0.0), 1e-12);
        assertEquals(0.05, AbReport.chiSquarePValue(1, 3.841458820694124), 1e-3);
        assertEquals(0.001, AbReport.chiSquarePValue(1, 10.827566170662733), 1e-3);
        assertEquals(0.05, AbReport.chiSquarePValue(2, 5.991464547107979), 1e-3);
        assertEquals(0.05, AbReport.chiSquarePValue(3, 7.814727903251179), 1e-3);
        assertEquals(0.05, AbReport.chiSquarePValue(10, 18.307038053275143), 1e-3);
        // deep in the tail the survival function must approach 0, never go negative
        double deep = AbReport.chiSquarePValue(1, 64.0);
        assertTrue(deep > 0 && deep < 1e-12, "p for chi2=64/df=1 was " + deep);
    }

    @Test
    void logGammaMatchesKnownValues() {
        assertEquals(0.5723649429247001, AbReport.logGamma(0.5), 1e-12); // ln sqrt(pi)
        assertEquals(3.1780538303479458, AbReport.logGamma(5.0), 1e-12); // ln 24
        assertEquals(0.0, AbReport.logGamma(1.0), 1e-12);
    }

    @Test
    void uniformDrawIsConsistentWithZeroChiSquare() {
        AbReport.Result result = AbReport.compute(List.of(v("A", 1, 50), v("B", 1, 50)), List.of());
        assertEquals(AbReport.Verdict.CONSISTENT, result.verdict());
        assertEquals(0.0, result.chiSquare(), 1e-12);
        assertEquals(1.0, result.pValue(), 1e-12);
        assertEquals(100, result.totalClicks());
        assertEquals(1, result.degreesOfFreedom());
        AbReport.Row a = result.rows().get(0);
        assertEquals(50.0, a.expected(), 1e-9);
        assertEquals(0.5, a.share(), 1e-12);
        // Wilson 95% for 50/100 is the textbook [0.4038, 0.5962]
        assertEquals(0.40383, a.ciLow(), 1e-3);
        assertEquals(0.59617, a.ciHigh(), 1e-3);
    }

    @Test
    void weightedExpectationsFollowWeightsNotCounts() {
        AbReport.Result fit = AbReport.compute(List.of(v("A", 3, 75), v("B", 1, 25)), List.of());
        assertEquals(AbReport.Verdict.CONSISTENT, fit.verdict());
        assertEquals(75.0, fit.rows().get(0).expected(), 1e-9);
        assertEquals(25.0, fit.rows().get(1).expected(), 1e-9);

        AbReport.Result skewed = AbReport.compute(List.of(v("A", 3, 60), v("B", 1, 40)), List.of());
        assertEquals(12.0, skewed.chiSquare(), 1e-9);
        assertEquals(AbReport.Verdict.SKEWED, skewed.verdict());
    }

    @Test
    void lopsidedDrawIsSkewed() {
        AbReport.Result result = AbReport.compute(List.of(v("A", 1, 90), v("B", 1, 10)), List.of());
        assertEquals(64.0, result.chiSquare(), 1e-9);
        assertEquals(AbReport.Verdict.SKEWED, result.verdict());
        assertTrue(result.pValue() < 0.001);
    }

    @Test
    void tooFewClicksAreInsufficient() {
        AbReport.Result result = AbReport.compute(List.of(v("A", 1, 10), v("B", 1, 5)), List.of());
        assertEquals(AbReport.Verdict.INSUFFICIENT_DATA, result.verdict());
        assertNull(result.chiSquare());
        assertNull(result.pValue());
    }

    @Test
    void starvedVariantExpectationIsInsufficient() {
        // total is ample, but B's expected count is ~0.99 < 5: the fit would be noise
        AbReport.Result result = AbReport.compute(List.of(v("A", 100, 100), v("B", 1, 0)), List.of());
        assertEquals(AbReport.Verdict.INSUFFICIENT_DATA, result.verdict());
    }

    @Test
    void singleVariantHasNoFit() {
        AbReport.Result result = AbReport.compute(List.of(v("A", 1, 100)), List.of());
        assertEquals(AbReport.Verdict.NOT_APPLICABLE, result.verdict());
        assertEquals(100.0, result.rows().get(0).expected(), 1e-9);
        assertEquals(0, result.degreesOfFreedom());
        assertNull(result.chiSquare());
    }

    @Test
    void orphanBucketsRideAlongOutsideTheFit() {
        AbReport.Result result = AbReport.compute(
                List.of(v("A", 1, 50), v("B", 1, 50)),
                List.of(v("C", 0, 20)));
        assertEquals(AbReport.Verdict.CONSISTENT, result.verdict());
        assertEquals(100, result.totalClicks(), "orphan clicks stay out of the fit population");
        assertEquals(3, result.rows().size());
        AbReport.Row orphan = result.rows().get(2);
        assertTrue(orphan.orphaned());
        assertNull(orphan.weight());
        assertNull(orphan.expected());
        assertNull(orphan.share());
        assertEquals(20, orphan.observed());
    }

    @Test
    void emptyWindowIsInsufficientNotBroken() {
        AbReport.Result result = AbReport.compute(List.of(v("A", 1, 0), v("B", 1, 0)), List.of());
        assertEquals(AbReport.Verdict.INSUFFICIENT_DATA, result.verdict());
        assertNull(result.rows().get(0).share());
    }
}
