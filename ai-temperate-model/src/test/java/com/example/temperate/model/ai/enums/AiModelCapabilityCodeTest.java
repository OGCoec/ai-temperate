package com.example.temperate.model.ai.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 验证模型能力枚举与数据库、HTTP 和前端共享的十三项稳定代码完全一致。
 */
final class AiModelCapabilityCodeTest {

    @Test
    void exposesExactlyThePersistedCapabilityCodesInStableOrder() {
        assertThat(Arrays.stream(AiModelCapabilityCode.values())
                .map(Enum::name)
                .toList())
                .containsExactly(
                        "CHAT_COMPLETIONS",
                        "RESPONSES",
                        "WEB_SEARCH",
                        "IMAGE_INPUT",
                        "IMAGE_GENERATION",
                        "IMAGE_EDIT",
                        "AUDIO_INPUT",
                        "AUDIO_GENERATION",
                        "AUDIO_EDIT",
                        "VIDEO_INPUT",
                        "VIDEO_GENERATION",
                        "VIDEO_EDIT",
                        "VIDEO_EXTENSION");
    }

    @Test
    void parsesCanonicalCodesAndRejectsRemovedAggregateCodes() {
        assertThat(AiModelCapabilityCode.fromExternalCode(" image_input "))
                .isEqualTo(AiModelCapabilityCode.IMAGE_INPUT);
        assertThat(AiModelCapabilityCode.fromExternalCode("VIDEO_EDIT"))
                .isEqualTo(AiModelCapabilityCode.VIDEO_EDIT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiModelCapabilityCode.fromExternalCode("IMAGE"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiModelCapabilityCode.fromExternalCode("AUDIO"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiModelCapabilityCode.fromExternalCode("VIDEO"));
    }
}
