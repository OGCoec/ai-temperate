package com.example.temperate.web.aimodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.PublicIdCodec;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 模型 PathVariable Converter 只接受统一 codec 生成的规范 Base64URL 公共 ID。
 */
final class AiModelPublicIdConverterTest {

    private final PublicIdCodec codec = new PublicIdCodec();
    private final AiModelPublicIdConverter converter = new AiModelPublicIdConverter(codec);

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
