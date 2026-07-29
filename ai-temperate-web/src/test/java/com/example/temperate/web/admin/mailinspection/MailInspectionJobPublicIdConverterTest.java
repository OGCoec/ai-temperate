package com.example.temperate.web.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * 验证邮箱检查任务 PathVariable 只接受专用 Codec 生成的规范 22 字符 Base64URL ID。
 */
final class MailInspectionJobPublicIdConverterTest {

    private final HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
    private final MailInspectionJobPublicIdConverter converter =
            new MailInspectionJobPublicIdConverter(codec);

    @Test
    void returnsValidatedCanonicalTextWithoutExposingLong() {
        String publicId = codec.encode(HexFormat.of().parseHex(
                "00112233445566778899aabbccddeeff"));

        MailInspectionJobPublicId converted = converter.convert(publicId);

        assertThat(converted.value()).isEqualTo(publicId);
    }

    @Test
    void rejectsPaddingAndWrongLength() {
        assertThatThrownBy(() -> converter.convert("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert(
                "AAAAAAAAAAAAAAAAAAAAAA="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert(
                "AAAAAAAAAAAAAAAAAAAAA"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
