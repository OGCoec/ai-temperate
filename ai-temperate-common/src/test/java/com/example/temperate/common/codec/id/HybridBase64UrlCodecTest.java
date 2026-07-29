package com.example.temperate.common.codec.id;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 128 位混合 ID 的固定长度 Base64URL 编码、规范重编码与非法输入拒绝边界。
 */
final class HybridBase64UrlCodecTest {

    private final HybridBase64UrlCodec codec = new HybridBase64UrlCodec();

    @Test
    void encodesSixteenBytesAsTwentyTwoUrlSafeCharacters() {
        byte[] value = new byte[HybridBase64UrlCodec.BINARY_LENGTH];
        Arrays.fill(value, (byte) 0xff);

        String encoded = codec.encode(value);

        assertEquals(HybridBase64UrlCodec.ENCODED_LENGTH, encoded.length());
        assertTrue(encoded.matches(HybridBase64UrlCodec.FORMAT));
        assertTrue(!encoded.contains("="));
    }

    @Test
    void roundTripsAllBinaryBits() {
        byte[] value = {
                0, 1, 2, 3, 4, 5, 6, 7,
                (byte) 0x80, (byte) 0x90, (byte) 0xa0, (byte) 0xb0,
                (byte) 0xc0, (byte) 0xd0, (byte) 0xe0, (byte) 0xff
        };

        assertArrayEquals(value, codec.decode(codec.encode(value)));
    }

    @Test
    void rejectsNullWrongLengthPaddingAndNonUrlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("A".repeat(21)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("A".repeat(21) + "="));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("A".repeat(21) + "+"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("A".repeat(21) + "/"));
    }

    @Test
    void rejectsNonCanonicalTrailingBits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode("AAAAAAAAAAAAAAAAAAAAAB"));
    }
}
