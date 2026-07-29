package com.example.temperate.web.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.PublicIdCodec;
import org.junit.jupiter.api.Test;

/**
 * 验证邮箱检查任务 PathVariable 只接受统一 Codec 生成的规范正数 Base64URL ID。
 */
final class MailInspectionJobPublicIdConverterTest {

    private final PublicIdCodec codec = new PublicIdCodec();
    private final MailInspectionJobPublicIdConverter converter =
            new MailInspectionJobPublicIdConverter(codec);

    @Test
    void returnsValidatedTextAndDecodedInternalId() {
        String publicId = codec.encode(55L);

        MailInspectionJobPublicId converted = converter.convert(publicId);

        assertThat(converted.value()).isEqualTo(publicId);
        assertThat(converted.internalId()).isEqualTo(55L);
    }

    @Test
    void rejectsPaddingWrongLengthAndZero() {
        assertThatThrownBy(() -> converter.convert("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("AAAAAAAAAAA="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("AAAAAAAAAAA"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
