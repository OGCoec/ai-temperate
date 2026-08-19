package com.example.temperate.common.codec.id;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 Hybrid Worker 的 16 字节 ID 只能以固定 26 字符规范 ULID 对外表示。
 */
final class HybridUlidCodecTest {

    private final HybridUlidCodec codec = new HybridUlidCodec();

    @Test
    void roundTripsAllBinaryBitsAsCanonicalUppercaseUlid() {
        byte[] value = {
                0x01, (byte) 0x9b, 0x12, 0x34, 0x56, 0x78, 0x01, 0x02,
                (byte) 0x80, (byte) 0x90, (byte) 0xa0, (byte) 0xb0,
                (byte) 0xc0, (byte) 0xd0, (byte) 0xe0, (byte) 0xff
        };

        String encoded = codec.encode(value);

        assertEquals(HybridUlidCodec.ENCODED_LENGTH, encoded.length());
        assertTrue(encoded.matches(HybridUlidCodec.ENCODED_PATTERN));
        assertArrayEquals(value, codec.decode(encoded));
    }

    @Test
    void preservesUnsignedBinaryOrdering() {
        byte[] earlier = new byte[HybridUlidCodec.BINARY_LENGTH];
        byte[] later = new byte[HybridUlidCodec.BINARY_LENGTH];
        earlier[5] = 1;
        later[5] = 2;

        assertTrue(codec.encode(earlier).compareTo(codec.encode(later)) < 0);
    }

    @Test
    void rejectsZeroWrongLengthLowercaseAndAmbiguousCharacters() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("0".repeat(25)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("8" + "0".repeat(25)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("0".repeat(25) + "a"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("0".repeat(25) + "I"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("0".repeat(26)));
    }

    @Test
    void decodeReturnsIndependentByteArrays() {
        byte[] value = new byte[HybridUlidCodec.BINARY_LENGTH];
        Arrays.fill(value, (byte) 1);
        String encoded = codec.encode(value);

        byte[] first = codec.decode(encoded);
        first[0] = 0;

        assertArrayEquals(value, codec.decode(encoded));
    }
}
