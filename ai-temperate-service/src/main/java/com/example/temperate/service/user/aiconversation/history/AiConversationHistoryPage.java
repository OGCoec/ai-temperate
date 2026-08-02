package com.example.temperate.service.user.aiconversation.history;

import java.util.List;

/**
 * 表示一页按时间正序展示的完整消息历史和继续向前读取所需的消息公共 ID。
 */
public record AiConversationHistoryPage(
        List<AiConversationHistoryMessage> messages,
        String nextBefore,
        boolean hasMore) {

    public AiConversationHistoryPage {
        messages = List.copyOf(messages);
    }
}
