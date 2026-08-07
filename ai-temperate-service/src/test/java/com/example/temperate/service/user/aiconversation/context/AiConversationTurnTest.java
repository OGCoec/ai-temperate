package com.example.temperate.service.user.aiconversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证只有持久轮次和用户主动停止的部分轮次能够进入后续模型上下文。
 */
final class AiConversationTurnTest {

    @Test
    void interruptedTurnWithoutTrustedSourceIsExcluded() {
        AiConversationTurn turn = interrupted(null);

        assertThat(turn.includedInPrompt()).isFalse();
    }

    @Test
    void onlyUserStopInterruptedTurnIsIncluded() {
        assertThat(interrupted(AiConversationInterruptionSource.USER_STOP)
                .includedInPrompt()).isTrue();
        assertThat(interrupted(AiConversationInterruptionSource.TRANSPORT_DISCONNECT)
                .includedInPrompt()).isFalse();
        assertThat(interrupted(AiConversationInterruptionSource.SYSTEM_FAILURE)
                .includedInPrompt()).isFalse();
    }

    private static AiConversationTurn interrupted(
            AiConversationInterruptionSource source) {
        return new AiConversationTurn(
                "usage",
                null,
                1L,
                new AiConversationContent("user", List.of()),
                new AiConversationContent("assistant", List.of()),
                AiConversationTurnState.INTERRUPTED,
                source,
                32L);
    }
}
