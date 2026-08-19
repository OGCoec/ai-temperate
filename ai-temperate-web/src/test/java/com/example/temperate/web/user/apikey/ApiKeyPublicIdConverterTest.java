package com.example.temperate.web.user.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridUlidCodec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来保证 API Key PathVariable 只接受规范大写 ULID，并把 16 字节内部值以防御性副本交给 Controller。
 */
final class ApiKeyPublicIdConverterTest {

    private final HybridUlidCodec codec = new HybridUlidCodec();
    private final ApiKeyPublicIdConverter converter = new ApiKeyPublicIdConverter(codec);

    @Test
    void convertsCanonicalUlidAndProtectsTheInternalArray() {
        byte[] expected = new byte[16];
        expected[15] = 7;
        String encoded = codec.encode(expected);

        ApiKeyPublicId converted = converter.convert(encoded);
        byte[] exposed = converted.internalValue();
        exposed[15] = 0;

        assertThat(converted.encoded()).isEqualTo(encoded);
        assertThat(converted.internalValue()).containsExactly(expected);
    }

    @Test
    void rejectsLegacyLowercaseAndZeroValues() {
        assertThatThrownBy(() -> converter.convert("AAAAAAAAAAE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("0".repeat(26)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert(codec.encode(nonZero()).toLowerCase()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] nonZero() {
        byte[] value = new byte[16];
        Arrays.fill(value, (byte) 0xff);
        return value;
    }
}
