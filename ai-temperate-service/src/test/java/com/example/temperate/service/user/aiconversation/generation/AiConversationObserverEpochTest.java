package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证旧 SSE 连接和旧延迟检查不能覆盖已经重连的新观察者状态。
 */
class AiConversationObserverEpochTest {

    @Test
    void onlyCurrentEpochMayDetachOrExpireAnObserver() {
        assertThat(AiConversationObserverEpoch.matches(8, 8)).isTrue();
        assertThat(AiConversationObserverEpoch.matches(7, 8)).isFalse();
    }
}
