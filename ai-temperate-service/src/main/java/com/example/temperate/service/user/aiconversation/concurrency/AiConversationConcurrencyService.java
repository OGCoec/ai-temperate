package com.example.temperate.service.user.aiconversation.concurrency;

import java.util.Optional;

/**
 * 定义 AI 会话跨实例全局和单用户并发额度的原子获取、续租与释放边界。
 */
public interface AiConversationConcurrencyService {

    default Optional<AiConversationConcurrencyPermit> tryAcquire(long userId) {
        return tryAcquire(userId, (short) 1);
    }

    Optional<AiConversationConcurrencyPermit> tryAcquire(
            long userId,
            short weight);

    boolean renew(AiConversationConcurrencyPermit permit);

    void release(AiConversationConcurrencyPermit permit);
}
