package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkTagsTest {

    @Test
    void normalizeTrimsLowercasesAndDedupesInFirstSeenOrder() {
        assertEquals("dev,prod", LinkTags.normalize(List.of(" Dev ", "PROD", "dev", "prod")));
    }

    @Test
    void normalizeSkipsBlankEntriesAndEmptiesToNull() {
        assertNull(LinkTags.normalize(null));
        assertNull(LinkTags.normalize(List.of()));
        assertNull(LinkTags.normalize(List.of("", "   ")));
        assertEquals("ops", LinkTags.normalize(List.of("", " ops ")));
    }

    @Test
    void normalizeRejectsOutOfPatternTags() {
        assertEquals(400, assertThrows(BizException.class,
                () -> LinkTags.normalize(List.of("bad tag"))).getHttpStatus());
        assertThrows(BizException.class, () -> LinkTags.normalize(List.of("has,comma")));
        assertThrows(BizException.class, () -> LinkTags.normalize(List.of("x".repeat(33))));
        assertThrows(BizException.class, () -> LinkTags.normalize(List.of("dot.tag")));
    }

    @Test
    void normalizeCapsAtTenTags() {
        List<String> ten = IntStream.range(0, 10).mapToObj(i -> "t" + i).toList();
        assertEquals(String.join(",", ten), LinkTags.normalize(ten));
        List<String> eleven = IntStream.range(0, 11).mapToObj(i -> "t" + i).toList();
        assertThrows(BizException.class, () -> LinkTags.normalize(eleven));
        // Duplicates collapse BEFORE the cap: 11 submissions, 10 distinct, is fine.
        List<String> withDupe = IntStream.range(0, 11).mapToObj(i -> "t" + (i % 10)).toList();
        assertEquals(String.join(",", ten), LinkTags.normalize(withDupe));
    }

    @Test
    void splitRoundTripsAndToleratesNull() {
        assertEquals(List.of(), LinkTags.split(null));
        assertEquals(List.of(), LinkTags.split(""));
        assertEquals(List.of("dev", "prod"), LinkTags.split("dev,prod"));
        assertTrue(LinkTags.isValidTag("dev_ops-2"));
    }
}
