package com.linkforge.link.support;

import com.linkforge.link.entity.LinkVariant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VariantPickerTest {

    private static LinkVariant variant(String key, int weight) {
        LinkVariant variant = new LinkVariant();
        variant.setVariantKey(key);
        variant.setWeight(weight);
        return variant;
    }

    @Test
    void singleVariantIsAlwaysPicked() {
        LinkVariant only = variant("A", 1);
        for (double r : new double[] {0.0, 0.3, 0.7, 0.9999999999999999}) {
            assertSame(only, VariantPicker.pick(List.of(only), () -> r));
        }
    }

    @Test
    void sevenThreeBoundarySplitsAtExactlyZeroPointSeven() {
        List<LinkVariant> ab = List.of(variant("A", 7), variant("B", 3));
        assertSame(ab.get(0), VariantPicker.pick(ab, () -> 0.6999));
        assertSame(ab.get(1), VariantPicker.pick(ab, () -> 0.7));
    }

    @Test
    void extremeHighDrawStillLandsOnTheLastVariant() {
        List<LinkVariant> ab = List.of(variant("A", 9999), variant("B", 10000));
        double largestBelowOne = Double.longBitsToDouble(Double.doubleToRawLongBits(1.0) - 1);
        assertSame(ab.get(1), VariantPicker.pick(ab, () -> largestBelowOne));
    }

    @Test
    void drawDistributionIsIndependentOfListOrder() {
        LinkVariant a = variant("A", 7);
        LinkVariant b = variant("B", 3);
        assertEquals(700, share(a, List.of(a, b)));
        assertEquals(300, share(b, List.of(a, b)));
        assertEquals(700, share(a, List.of(b, a)));
        assertEquals(300, share(b, List.of(b, a)));
    }

    /** Over a 1000-point grid each variant must own exactly its weight share of draws. */
    private static int share(LinkVariant target, List<LinkVariant> order) {
        int picked = 0;
        for (int i = 0; i < 1000; i++) {
            double r = i / 1000.0;
            if (VariantPicker.pick(order, () -> r) == target) {
                picked++;
            }
        }
        return picked;
    }
}
