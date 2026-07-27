package com.example.temperate.web.admin.aimodelicon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.PublicIdCodec;
import org.junit.jupiter.api.Test;

/**
 * 验证模型图标 PathVariable 只接受统一编码器生成的 11 位规范 Base64URL 公共 ID。
 */
final class AiModelIconPublicIdConverterTest {

    private final PublicIdCodec codec = new PublicIdCodec();
    private final AiModelIconPublicIdConverter converter =
            new AiModelIconPublicIdConverter(codec);

    @Test
    void acceptsCanonicalPositivePublicId() {
        String encoded = codec.encode(123L);

        assertThat(converter.convert(encoded).value()).isEqualTo(encoded);
    }

    @Test
    void rejectsWrongLengthPaddingAndNonPositiveValues() {
        assertThatThrownBy(() -> converter.convert("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("AAAAAAAAAAA="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("AAAAAAAAAAA"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
