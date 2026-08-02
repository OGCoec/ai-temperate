package com.example.temperate.service.user.aiconversation.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证普通用户推理强度的短整数协议只映射到受控的 CLIProxyAPI 参数。
 */
final class AiConversationReasoningEffortTest {

    @Test
    void mapsAllPublicLevelsToCliProxyValues() {
        assertThat(AiConversationReasoningEffort.fromLevel((short) 1))
                .isEqualTo(AiConversationReasoningEffort.LOW);
        assertThat(AiConversationReasoningEffort.fromLevel((short) 2))
                .isEqualTo(AiConversationReasoningEffort.MEDIUM);
        assertThat(AiConversationReasoningEffort.fromLevel((short) 3))
                .isEqualTo(AiConversationReasoningEffort.HIGH);
        assertThat(AiConversationReasoningEffort.fromLevel((short) 4))
                .isEqualTo(AiConversationReasoningEffort.EXTRA_HIGH);
        assertThat(AiConversationReasoningEffort.fromLevel((short) 5))
                .isEqualTo(AiConversationReasoningEffort.ULTRA);

        assertThat(AiConversationReasoningEffort.values())
                .extracting(AiConversationReasoningEffort::upstreamValue)
                .containsExactly("low", "medium", "high", "xhigh", "max");
    }

    @Test
    void defaultsMissingLevelToMediumAndPublishesImmutableLevels() {
        assertThat(AiConversationReasoningEffort.fromLevel(null))
                .isEqualTo(AiConversationReasoningEffort.MEDIUM);
        assertThat(AiConversationReasoningEffort.defaultLevel()).isEqualTo((short) 2);
        assertThat(AiConversationReasoningEffort.supportedLevels())
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
        assertThatThrownBy(() ->
                AiConversationReasoningEffort.supportedLevels().add((short) 6))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownShortLevelsAsInvalidRequests() {
        for (short level : List.of((short) 0, (short) 6, Short.MAX_VALUE)) {
            assertThatThrownBy(() -> AiConversationReasoningEffort.fromLevel(level))
                    .isInstanceOf(AiConversationException.class)
                    .extracting(exception ->
                            ((AiConversationException) exception).code())
                    .isEqualTo(AiConversationErrorCode.AI_REQUEST_INVALID);
        }
    }
}
