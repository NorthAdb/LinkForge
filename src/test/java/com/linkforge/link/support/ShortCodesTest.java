package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCodesTest {

    @Test
    void respectsLengthAndAlphabet() {
        for (int i = 0; i < 500; i++) {
            String code = ShortCodes.randomCode(7);
            assertEquals(7, code.length());
            assertTrue(ShortCodes.inAlphabet(code), "unexpected char in " + code);
        }
    }

    @Test
    void samplerProducesDistinctCodes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(ShortCodes.randomCode(7));
        }
        assertTrue(seen.size() > 990, "7 chars over 62 alphabet must be near-unique, got "
                + seen.size() + "/1000");
    }
}
