package com.linkforge.link.support;

import com.linkforge.link.entity.LinkVariant;

import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Pure weighted draw over a link's variants (D-C3-8); unit-tested with fixed
 * doubles, the redirect path supplies {@code ThreadLocalRandom.current()::nextDouble}.
 */
public final class VariantPicker {

    private VariantPicker() {
    }

    public static LinkVariant pick(List<LinkVariant> variants, DoubleSupplier random) {
        long total = 0;
        for (LinkVariant variant : variants) {
            total += variant.getWeight();
        }
        // r*total lands in [0, total); each variant owns the half-open step
        // [cumulativeBefore, cumulativeAfter), so the compare must be strict <.
        double threshold = random.getAsDouble() * total;
        long cumulative = 0;
        for (LinkVariant variant : variants) {
            cumulative += variant.getWeight();
            if (threshold < cumulative) {
                return variant;
            }
        }
        // Only reachable when r*total rounds up to exactly total; the top step owns it.
        return variants.get(variants.size() - 1);
    }
}
