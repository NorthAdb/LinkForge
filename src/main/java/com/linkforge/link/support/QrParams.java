package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;

/**
 * C1 wave B (D-C1-4): pure parser for the qr.png query params. Defaults per the
 * frozen decision — size 256, margin 1, ec M — and any invalid value is a 400
 * the global handler turns into the standard JSON body.
 */
public record QrParams(int size, int margin, char ec) {

    public static QrParams parse(String size, String margin, String ec) {
        return new QrParams(intIn(size, "size", 128, 1024, 256),
                intIn(margin, "margin", 0, 4, 1), ecIn(ec));
    }

    private static int intIn(String raw, String name, int min, int max, int dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new BizException(400, name + " must be " + min + "-" + max);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new BizException(400, name + " must be an integer");
        }
    }

    private static char ecIn(String raw) {
        if (raw == null || raw.isBlank()) {
            return 'M';
        }
        if (raw.length() == 1 && "LMQH".indexOf(raw.charAt(0)) >= 0) {
            return raw.charAt(0);
        }
        throw new BizException(400, "ec must be one of L, M, Q, H");
    }
}
