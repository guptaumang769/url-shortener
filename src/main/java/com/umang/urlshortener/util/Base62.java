package com.umang.urlshortener.util;

/**
 * Base62 ([0-9A-Za-z]) encoder/decoder for turning a numeric ID into a short URL-safe code.
 * Encoding a monotonic DB id (rather than hashing the URL) means codes can't collide, so
 * there's no "is this code taken?" check on insert. 7 chars ≈ 62^7 ≈ 3.5 trillion codes.
 */
public final class Base62 {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62() {
    }

    private static final int MIN_LENGTH = 3;

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        if (value == 0) {
            return "0".repeat(MIN_LENGTH);
        }
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            int remainder = (int) (value % BASE);
            sb.append(ALPHABET.charAt(remainder));
            value /= BASE;
        }
        while (sb.length() < MIN_LENGTH) {
            sb.append('0');
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base62 character: " + code.charAt(i));
            }
            value = value * BASE + digit;
        }
        return value;
    }
}
