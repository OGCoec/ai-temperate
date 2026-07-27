package com.example.temperate.web.admin.aimodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 模型强 ETag 只接受单个规范正版本，并拒绝弱标签、通配符和多值。
 */
final class AiModelVersionTagTest {

    @Test
    void formatsAndParsesCanonicalStrongVersion() {
        assertThat(AiModelVersionTag.format(17L)).isEqualTo("\"v17\"");
        assertThat(AiModelVersionTag.parseRequired("\"v17\"")).isEqualTo(17L);
    }

    @Test
    void rejectsMissingWeakWildcardAndMultipleTags() {
        assertInvalid(null);
        assertInvalid("");
        assertInvalid("W/\"v1\"");
        assertInvalid("*");
        assertInvalid("\"v1\", \"v2\"");
        assertInvalid("\"v0\"");
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> AiModelVersionTag.parseRequired(value))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_VERSION_REQUIRED));
    }
}
