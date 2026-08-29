package com.umang.urlshortener.util;

/**
 * Base62 encoder/decoder for turning a numeric ID into a short URL-safe code and back.
 *
 * <p>Why Base62? The alphabet [0-9A-Za-z] is 62 characters — every character is URL-safe
 * (no escaping) and case-sensitive, so 7 characters give 62^7 ≈ 3.5 trillion codes. That
 * is the standard technique behind bit.ly-style shorteners.
 *
 * <p>Why encode a DB-generated ID rather than hash the URL? A monotonic ID guarantees no
 * collisions by construction — we never have to check "is this code taken?" before insert.
 * Hashing the long URL (e.g. MD5→Base62) would need collision handling and re-hashing.
 * The trade-off: sequential IDs are guessable/enumerable, which we mitigate below.
 */
public final class Base62 {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            int remainder = (int) (value % BASE);
            sb.append(ALPHABET.charAt(remainder));
            value /= BASE;
        }
        // Digits were produced least-significant first; reverse to most-significant first.
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
