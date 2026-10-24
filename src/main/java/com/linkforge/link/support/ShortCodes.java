package com.linkforge.link.support;

import java.security.SecureRandom;

/** Random short-code generation over the 62-char alphabet, unit-testable. */
public final class ShortCodes {

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShortCodes() {
    }

    public static String randomCode(int length) {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }

    public static boolean inAlphabet(String code) {
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean found = false;
            for (char allowed : ALPHABET) {
                if (c == allowed) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
