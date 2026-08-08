package com.example.temperate.service.user.aiconversation.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import org.junit.jupiter.api.Test;

/**
 * 验证数据库 vendor 只能严格映射到已实现的供应商协议，并锁定各供应商公开的推理档位。
 */
final class AiModelProviderTest {

    @Test
    void mapsSupportedVendorsWithoutModelNameGuessing() {
        assertThat(AiModelProvider.fromVendor(" openai ")).isEqualTo(AiModelProvider.OPENAI);
        assertThat(AiModelProvider.fromVendor("XAI")).isEqualTo(AiModelProvider.XAI);
        assertThat(AiModelProvider.fromVendor("anthropic")).isEqualTo(AiModelProvider.ANTHROPIC);
        assertThat(AiModelProvider.fromVendor("google")).isEqualTo(AiModelProvider.GOOGLE);

        assertThatThrownBy(() -> AiModelProvider.fromVendor("gemini-3.1-flash"))
                .isInstanceOf(AiConversationException.class);
        assertThatThrownBy(() -> AiModelProvider.fromVendor("claude-opus"))
                .isInstanceOf(AiConversationException.class);
    }

    @Test
    void exposesProviderSpecificReasoningLevels() {
        assertThat(AiModelProvider.OPENAI.supportedReasoningLevels())
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
        assertThat(AiModelProvider.XAI.supportedReasoningLevels())
                .containsExactly((short) 1, (short) 2, (short) 3);
        assertThat(AiModelProvider.ANTHROPIC.supportedReasoningLevels())
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
        assertThat(AiModelProvider.GOOGLE.supportedReasoningLevels())
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4);
    }
}
