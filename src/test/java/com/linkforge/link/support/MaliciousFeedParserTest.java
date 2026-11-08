package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Batch 3b: the parser eats the URLhaus hostfile shape plus plain/URL lines. */
class MaliciousFeedParserTest {

    @Test
    void hostfileLinesYieldTheirHostColumn() {
        Set<String> hosts = MaliciousFeedParser.parse(
                "127.0.0.1\tevil.example\n127.0.0.1 spaced.example\n");
        assertEquals(Set.of("evil.example", "spaced.example"), hosts);
    }

    @Test
    void commentsBlanksAndLoopbackNamesAreSkipped() {
        Set<String> hosts = MaliciousFeedParser.parse(String.join("\n",
                "# URLhaus hostfile",
                "",
                "   ",
                "127.0.0.1 localhost",
                "localhost",
                "::1 localhost",
                "127.0.0.1 real.example"));
        assertEquals(Set.of("real.example"), hosts);
    }

    @Test
    void plainHostsAndUrlsAreAccepted() {
        Set<String> hosts = MaliciousFeedParser.parse(String.join("\n",
                "plain.example",
                "https://url.example/some/path?q=1",
                "http://with-port.example:8080/x"));
        assertEquals(Set.of("plain.example", "url.example", "with-port.example"), hosts);
    }

    @Test
    void entriesAreNormalizedLikeUrlHosts() {
        Set<String> hosts = MaliciousFeedParser.parse(
                "127.0.0.1 EVIL.example.\n127.0.0.1 bücher.example\n");
        assertEquals(Set.of("evil.example", "xn--bcher-kva.example"), hosts);
    }

    @Test
    void garbageLinesAreSkippedAndDuplicatesCollapse() {
        Set<String> hosts = MaliciousFeedParser.parse(String.join("\n",
                "!!!not-a-host!!!",
                "a..b.example",
                "127.0.0.1 dup.example",
                "127.0.0.1 dup.example"));
        assertEquals(Set.of("dup.example"), hosts);
    }

    @Test
    void emptyInputParsesToEmpty() {
        assertTrue(MaliciousFeedParser.parse("").isEmpty());
        assertTrue(MaliciousFeedParser.parse(null).isEmpty());
        assertTrue(MaliciousFeedParser.parse("# only comments\n").isEmpty());
    }
}
