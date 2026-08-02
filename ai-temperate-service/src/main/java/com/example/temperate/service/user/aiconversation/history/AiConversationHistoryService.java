package com.example.temperate.service.user.aiconversation.history;

/**
 * 定义普通用户读取自身有效会话侧栏和 PostgreSQL 完整消息历史的只读业务边界。
 */
public interface AiConversationHistoryService {

    AiConversationPage list(long userId, String cursor, int pageSize);

    AiConversationHistoryPage messages(
            long userId,
            byte[] conversationId,
            String beforeMessagePublicId,
            int pageSize);
}
