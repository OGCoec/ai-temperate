package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.time.Duration;
import java.util.Optional;

/**
 * 定义直接 SSE 活动实例和显式 Stop 意图的短期 Redis 协调边界，不保存模型正文或原始幂等键。
 */
public interface AiConversationDirectResponseControlStore {

    void registerOwner(
            HmacIdentifier requestIdentifier,
            String instanceId,
            Duration timeToLive);

    Optional<String> findOwner(HmacIdentifier requestIdentifier);

    boolean requestUserStop(
            HmacIdentifier requestIdentifier,
            Duration timeToLive);

    boolean userStopRequested(HmacIdentifier requestIdentifier);

    void clearOwner(
            HmacIdentifier requestIdentifier,
            String instanceId);

    void clearUserStop(HmacIdentifier requestIdentifier);
}
