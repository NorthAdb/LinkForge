package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** D-C1-4 param contract: defaults 256/1/M and hard bounds 128-1024, 0-4, L/M/Q/H. */
class QrParamsTest {

    @Test
    void defaultsAre256Margin1EcM() {
        assertEquals(new QrParams(256, 1, 'M'), QrParams.parse(null, null, null));
        assertEquals(new QrParams(256, 1, 'M'), QrParams.parse("", "", ""));
    }

    @Test
    void validBoundsParse() {
        assertEquals(new QrParams(128, 0, 'L'), QrParams.parse("128", "0", "L"));
        assertEquals(new QrParams(1024, 4, 'H'), QrParams.parse("1024", "4", "H"));
        assertEquals(new QrParams(512, 2, 'Q'), QrParams.parse("512", "2", "Q"));
    }

    @Test
    void outOfBoundsOrGarbageIsA400() {
        assertBiz(400, () -> QrParams.parse("127", null, null), "size below 128");
        assertBiz(400, () -> QrParams.parse("1025", null, null), "size above 1024");
        assertBiz(400, () -> QrParams.parse("abc", null, null), "size not an integer");
        assertBiz(400, () -> QrParams.parse(null, "-1", null), "margin below 0");
        assertBiz(400, () -> QrParams.parse(null, "5", null), "margin above 4");
        assertBiz(400, () -> QrParams.parse(null, null, "X"), "unknown ec level");
        assertBiz(400, () -> QrParams.parse(null, null, "m"), "ec is case-sensitive");
    }

    private static void assertBiz(int expectedCode, Runnable call, String message) {
        BizException ex = assertThrows(BizException.class, call::run, message);
        assertEquals(expectedCode, ex.getCode(), message);
    }
}
