package com.example.temperate.service.user.aiconversation.context;

/**
 * 表示会话上下文中的一轮用户输入和助手输出，可来自数据库持久化消息或 Redis 临时覆盖层。
 */
public record AiConversationTurn(
        String reference,
        Long messageId,
        Long ordinal,
        AiConversationContent user,
        AiConversationContent assistant,
        AiConversationTurnState state) {

    public boolean ephemeral() {
        return state != AiConversationTurnState.PERSISTED;
    }
}
