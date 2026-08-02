package com.example.temperate.service.user.aiconversation.lease;

import java.util.Optional;

/**
 * 定义 AI 会话生成和压缩租约的获取、续期及带所有者校验的释放边界。
 */
public interface AiConversationLeaseService {

    Optional<AiConversationLease> tryAcquire(
            String conversationPublicId, AiConversationLeaseType type);

    boolean renew(AiConversationLease lease);

    void release(AiConversationLease lease);
}
