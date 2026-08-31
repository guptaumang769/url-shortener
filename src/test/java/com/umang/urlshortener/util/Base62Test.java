package com.umang.urlshortener.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Base62Test {

    @Test
    void encodesZeroWithMinimumPadding() {
        assertThat(Base62.encode(0)).isEqualTo("000");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 61, 62, 63, 3844, 1_000_000, 9_999_999_999L, Long.MAX_VALUE})
    void encodeThenDecodeIsIdentity(long value) {
        String code = Base62.encode(value);
        assertThat(Base62.decode(code)).isEqualTo(value);
    }

    @Test
    void encodingIsCollisionFreeAcrossASweepOfIds() {
        // Sequential ids must map to distinct codes — the property that lets us skip
        // any "is this code taken?" check on insert.
        Set<String> codes = new HashSet<>();
        for (long id = 1; id <= 100_000; id++) {
            assertThat(codes.add(Base62.encode(id))).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 61, 62})
    void encodedCodeIsAtLeastThreeChars(long value) {
        assertThat(Base62.encode(value).length()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void rejectsNegativeValues() {
        assertThatThrownBy(() -> Base62.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCharactersOnDecode() {
        assertThatThrownBy(() -> Base62.decode("abc-def"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
