package com.linkforge.stats.support;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch 3b: A/B distribution-fit diagnostic. The only observable this service
 * has is the click itself, and a variant's click count is produced BY the
 * weighted draw — so this is deliberately NOT a conversion test. It compares
 * the observed variant distribution against the configured weights
 * (chi-square goodness of fit, Wilson score intervals on the shares): a
 * SKEWED verdict means the draw pipeline misbehaves (picker bug, a cache
 * pinning one variant, a routing rule eating a variant's traffic), which is
 * a real, detectable fault class. Pure functions, unit-tested against known
 * chi-square critical values.
 */
public final class AbReport {

    public enum Verdict {CONSISTENT, SKEWED, INSUFFICIENT_DATA, NOT_APPLICABLE}

    /**
     * S/N wave (audit S1): why a report is NOT_APPLICABLE before any fit is
     * attempted. The names are a stable wire contract — once shipped they do
     * not get renamed. Null on every verdict that carries a fit conclusion
     * (and on INSUFFICIENT_DATA, where the fit exists but starved).
     */
    public enum NotApplicableReason {
        /** A currently configured variant effectively narrows its candidate
         * set (conditional routing), so a global-weight fit is meaningless. */
        CONDITIONAL_ROUTING,
        /** The configuration demonstrably changed inside the report window:
         * orphaned buckets exist, or the current set was installed inside
         * the window after the link's birth. */
        CONFIG_CHANGED_IN_WINDOW,
        /** The current set was installed inside the window but a change
         * cannot be told apart from the link's birth install — stability is
         * unprovable either way, so no fit is shipped. */
        CONFIG_HISTORY_UNKNOWN,
        /** Structural: fewer than two configured variants, no fit exists. */
        TOO_FEW_VARIANTS
    }

    /** One currently configured variant and its observed clicks in the window. */
    public record VariantInput(String key, int weight, long observed) {
    }

    /** One output row. Orphaned rows are buckets of variants deleted since the
     * clicks happened: they carry no weight, take no part in the fit, and their
     * expected/share/CI stay null. */
    public record Row(String key, Integer weight, long observed, Double expected,
                      Double share, Double ciLow, Double ciHigh, boolean orphaned) {
    }

    public record Result(List<Row> rows, long totalClicks, Double chiSquare,
                         int degreesOfFreedom, Double pValue, Verdict verdict,
                         NotApplicableReason reason) {
    }

    private static final double Z_95 = 1.96;
    private static final double ALPHA = 0.05;
    private static final long MIN_TOTAL_CLICKS = 30;
    private static final double MIN_EXPECTED = 5.0;

    private AbReport() {
    }

    /**
     * @param variants current configuration (weight order preserved in the output)
     * @param orphans  observed clicks of variants no longer configured, key order
     */
    public static Result compute(List<VariantInput> variants, List<VariantInput> orphans) {
        long total = variants.stream().mapToLong(VariantInput::observed).sum();
        long totalWeight = variants.stream().mapToLong(VariantInput::weight).sum();
        List<Row> rows = new ArrayList<>(variants.size() + orphans.size());
        for (VariantInput v : variants) {
            Double expected = totalWeight > 0 ? (double) total * v.weight() / totalWeight : 0.0;
            rows.add(rowWithInterval(v, expected, total, false));
        }
        for (VariantInput o : orphans) {
            rows.add(new Row(o.key(), null, o.observed(), null, null, null, null, true));
        }
        if (variants.size() < 2 || totalWeight <= 0) {
            // Zero variants = plain link; one variant leaves df = 0 — no fit exists.
            return new Result(rows, total, null, 0, null, Verdict.NOT_APPLICABLE,
                    NotApplicableReason.TOO_FEW_VARIANTS);
        }
        boolean starved = total < MIN_TOTAL_CLICKS || variants.stream().anyMatch(v ->
                totalWeight > 0 && (double) total * v.weight() / totalWeight < MIN_EXPECTED);
        if (starved) {
            return new Result(rows, total, null, variants.size() - 1, null, Verdict.INSUFFICIENT_DATA, null);
        }
        double chiSquare = 0.0;
        for (VariantInput v : variants) {
            double expected = (double) total * v.weight() / totalWeight;
            double diff = v.observed() - expected;
            chiSquare += diff * diff / expected;
        }
        int df = variants.size() - 1;
        double p = chiSquarePValue(df, chiSquare);
        return new Result(rows, total, chiSquare, df, p, p < ALPHA ? Verdict.SKEWED : Verdict.CONSISTENT, null);
    }

    /**
     * S/N wave (audit S1): the applicability gate, evaluated BEFORE
     * {@link #compute}. The caller derives the inputs; this stays a pure
     * function so the precedence is unit-testable without a database.
     *
     * <p>Stability proof, and the invariant it rests on: variant rows are
     * NEVER updated in place — the only variant writes in the codebase are
     * whole-set inserts (create / CSV import) and whole-set delete+insert
     * replacement inside {@code ShortLinkService.update()}. Every row of a
     * set therefore carries the same app-side {@code createdAt}: the install
     * instant of the CURRENT configuration (keys, weights, rules, targets).
     * If that instant predates the window, the configuration present now is
     * the configuration every in-window click was drawn under. An in-place
     * variant edit path, if one is ever added, silently breaks this proof —
     * it must stamp a fresh install instant instead.
     *
     * @param anyConditional   any CURRENT variant's parsed rule narrows its
     *                         candidate set (parse-based; "{}" is not conditional)
     * @param orphansPresent   the window holds buckets of variants no longer
     *                         configured — proof the in-window configuration
     *                         differed from the current one
     * @param configInstalledAt max {@code createdAt} over the current variant
     *                         rows (null when unavailable: nothing is provable)
     * @param linkCreatedAt    the link's own {@code createdAt}; a replacement
     *                         must postdate it (beyond a 2s tolerance for the
     *                         separate {@code now()} calls and DATETIME's
     *                         second precision at birth) to count as a change
     * @param windowStart      start of the report window, derived exactly as
     *                         the caller derives its bucket window
     * @return the reason the fit does not apply, or null when the current
     *         configuration is PROVEN stable for the whole window
     */
    public static NotApplicableReason checkApplicability(boolean anyConditional,
                                                         boolean orphansPresent,
                                                         LocalDateTime configInstalledAt,
                                                         LocalDateTime linkCreatedAt,
                                                         LocalDateTime windowStart) {
        if (anyConditional) {
            return NotApplicableReason.CONDITIONAL_ROUTING;
        }
        if (orphansPresent) {
            return NotApplicableReason.CONFIG_CHANGED_IN_WINDOW;
        }
        if (configInstalledAt == null) {
            return NotApplicableReason.CONFIG_HISTORY_UNKNOWN;
        }
        if (configInstalledAt.isBefore(windowStart)) {
            return null;
        }
        if (linkCreatedAt != null && configInstalledAt.isAfter(linkCreatedAt.plusSeconds(2))) {
            return NotApplicableReason.CONFIG_CHANGED_IN_WINDOW;
        }
        return NotApplicableReason.CONFIG_HISTORY_UNKNOWN;
    }

    /**
     * The NOT_APPLICABLE result for a gate rejection: observed counts and
     * weights are window facts and stay, but every number that encodes the
     * (invalid) fit — expected, share, confidence interval — is null, so the
     * response cannot be misread as a significance conclusion.
     */
    public static Result notApplicable(List<VariantInput> variants, List<VariantInput> orphans,
                                       NotApplicableReason reason) {
        long total = variants.stream().mapToLong(VariantInput::observed).sum();
        List<Row> rows = new ArrayList<>(variants.size() + orphans.size());
        for (VariantInput v : variants) {
            rows.add(new Row(v.key(), v.weight(), v.observed(), null, null, null, null, false));
        }
        for (VariantInput o : orphans) {
            rows.add(new Row(o.key(), null, o.observed(), null, null, null, null, true));
        }
        return new Result(rows, total, null, 0, null, Verdict.NOT_APPLICABLE, reason);
    }

    private static Row rowWithInterval(VariantInput v, Double expected, long total, boolean orphaned) {
        if (total <= 0) {
            return new Row(v.key(), v.weight(), v.observed(), expected, null, null, null, orphaned);
        }
        double share = (double) v.observed() / total;
        double[] ci = wilson(share, total);
        return new Row(v.key(), v.weight(), v.observed(), expected, share, ci[0], ci[1], orphaned);
    }

    /** Wilson score interval for a binomial proportion at z = 1.96 (95%). */
    static double[] wilson(double share, long n) {
        double z2 = Z_95 * Z_95;
        double denom = 1.0 + z2 / n;
        double centre = (share + z2 / (2.0 * n)) / denom;
        double margin = Z_95 / denom * Math.sqrt(share * (1.0 - share) / n + z2 / (4.0 * n * n));
        return new double[]{Math.max(0.0, centre - margin), Math.min(1.0, centre + margin)};
    }

    /**
     * Survival function of the chi-square distribution: P(X > x) with
     * {@code df} degrees of freedom = Q(df/2, x/2), the regularized upper
     * incomplete gamma. Log-gamma by the Lanczos approximation (g = 7, the
     * Numerical Recipes coefficient set); the gamma itself by its series
     * representation below x = a + 1 and by continued fraction above it
     * (Numerical Recipes, gammp/gammq). Accurate to ~1e-12 in double precision.
     */
    public static double chiSquarePValue(int df, double chiSquare) {
        if (chiSquare <= 0) {
            return 1.0;
        }
        double a = df / 2.0;
        double x = chiSquare / 2.0;
        if (x < a + 1.0) {
            return 1.0 - gammaSeriesP(a, x);
        }
        return gammaContinuedFractionQ(a, x);
    }

    private static double gammaSeriesP(double a, double x) {
        if (x == 0) {
            return 0.0;
        }
        double ap = a;
        double sum = 1.0 / a;
        double delta = sum;
        for (int n = 0; n < 1000; n++) {
            ap += 1.0;
            delta *= x / ap;
            sum += delta;
            if (Math.abs(delta) < Math.abs(sum) * 1e-15) {
                break;
            }
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    private static double gammaContinuedFractionQ(double a, double x) {
        final double tiny = 1e-300;
        double b = x + 1.0 - a;
        double c = 1.0 / tiny;
        double d = Math.abs(b) < tiny ? 1.0 / tiny : 1.0 / b;
        double h = d;
        for (int i = 1; i <= 1000; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = b + an / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < 1e-15) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    private static final double[] LANCZOS = {
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    /**
     * Lanczos approximation of ln Γ(x) for x > 0, in the g=7 form that matches
     * the LANCZOS coefficients above: with z = x − 1 and A(z) = c₀ + Σ cₖ/(z+k),
     * ln Γ(x) = ½ln(2π) + (z+½)·ln(z+7.5) − (z+7.5) + ln A(z). (An earlier draft
     * paired these coefficients with the Numerical Recipes gammln skeleton —
     * different parametrization — which AbReportTest's known values caught.)
     * Below 0.5 the reflection formula Γ(x)Γ(1−x) = π/sin(πx) applies.
     */
    static double logGamma(double x) {
        if (x < 0.5) {
            return Math.log(Math.PI) - Math.log(Math.sin(Math.PI * x)) - logGamma(1.0 - x);
        }
        double z = x - 1.0;
        double a = LANCZOS[0];
        for (int k = 1; k < LANCZOS.length; k++) {
            a += LANCZOS[k] / (z + k);
        }
        double t = z + 7.5;
        return 0.5 * Math.log(2.0 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }
}
