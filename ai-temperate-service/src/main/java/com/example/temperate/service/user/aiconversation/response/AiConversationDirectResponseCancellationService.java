package com.example.temperate.service.user.aiconversation.response;

import java.util.UUID;

/**
 * 定义已认证用户按原始 UUIDv4 幂等键取消直接 SSE 请求的业务边界。
 */
public interface AiConversationDirectResponseCancellationService {

    AiConversationDirectResponseCancellationStatus requestUserStop(
            long userId,
            UUID idempotencyKey,
            String traceId);
}
