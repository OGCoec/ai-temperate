package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证异步生成、观察者、取消来源和唯一终态的持久化编码保持稳定，避免消息与数据库解释发生漂移。
 */
class AiConversationGenerationStateTest {

    @Test
    void keepsDetachedSeparateFromCancellationAndBillingTerminal() {
        assertThat(AiConversationGenerationObserverStatus.DETACHED.code()).isEqualTo(1);
        assertThat(AiConversationGenerationStatus.CANCEL_REQUESTED.code()).isEqualTo(2);
        assertThat(AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code())
                .isEqualTo(3);
    }

    @Test
    void exposesOnlyLockedCancelAndTerminalValues() {
        assertThat(AiConversationGenerationCancelSource.values())
                .extracting(Enum::name)
                .containsExactly("USER_STOP", "ADMIN_CANCEL", "CLIENT_EXIT_TIMEOUT");
        assertThat(AiConversationGenerationTerminalType.values())
                .extracting(Enum::name)
                .containsExactly(
                        "COMPLETED",
                        "CLIENT_CANCELLED",
                        "ADMIN_CANCELLED",
                        "UPSTREAM_FAILED",
                        "SYSTEM_FAILED");
    }
}
