package com.example.temperate.service.user.aiconversation.history;

import java.time.OffsetDateTime;

/**
 * 表示普通用户会话侧栏中的单条只读摘要，不暴露内部数据库 ID。
 */
public record AiConversationSummary(
        String conversationPublicId,
        String title,
        String lastMessagePublicId,
        OffsetDateTime createdAt) {
}
