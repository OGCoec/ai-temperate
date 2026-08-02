package com.example.temperate.service.user.aiconversation.history;

import java.util.List;

/**
 * 表示按最后消息倒序返回的一页会话侧栏以及下一页复合游标。
 */
public record AiConversationPage(
        List<AiConversationSummary> conversations,
        String nextCursor,
        boolean hasMore) {

    public AiConversationPage {
        conversations = List.copyOf(conversations);
    }
}
