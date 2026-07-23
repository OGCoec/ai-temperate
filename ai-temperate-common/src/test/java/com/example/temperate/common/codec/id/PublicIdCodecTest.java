package com.example.temperate.common.codec.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证内部 Long ID 与固定长度 Base64URL 公共 ID 之间的规范转换和非法输入拒绝行为。
 */
final class PublicIdCodecTest {

    private final PublicIdCodec codec = new PublicIdCodec();

    @Test
    void encodesPositiveLongAsFixedLengthUnpaddedBase64Url() {
        assertEquals("AAAAAAAAAAE", codec.encode(1L));
        assertEquals(11, codec.encode(Long.MAX_VALUE).length());
    }

    @Test
    void roundTripsPositiveLongValues() {
        assertEquals(1L, codec.decode(codec.encode(1L)));
        assertEquals(Long.MAX_VALUE, codec.decode(codec.encode(Long.MAX_VALUE)));
    }

    @Test
    void rejectsNonPositiveValuesDuringEncoding() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(0L));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(-1L));
    }

    @Test
    void rejectsNullWrongLengthIllegalCharactersAndPadding() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("AAAAAAAAAA"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("AAAAAAAAAA=="));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("AAAAAAAAAA+"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("AAAAAAAAAA/"));
    }

    @Test
    void rejectsDecodedZeroAndNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("AAAAAAAAAAA"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("__________8"));
    }

    @Test
    void rejectsNonCanonicalBase64UrlEncoding() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("AAAAAAAAAAF"));
    }
}
