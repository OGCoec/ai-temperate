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
        AiConversationTurnState state,
        AiConversationInterruptionSource interruptionSource) {

    public AiConversationTurn(
            String reference,
            Long messageId,
            Long ordinal,
            AiConversationContent user,
            AiConversationContent assistant,
            AiConversationTurnState state) {
        this(reference, messageId, ordinal, user, assistant, state, null);
    }

    public boolean ephemeral() {
        return state != AiConversationTurnState.PERSISTED;
    }

    /**
     * 只有用户明确停止的草稿允许进入下一次模型上下文；旧缓存没有来源时保持原兼容语义。
     */
    public boolean includedInPrompt() {
        return state == AiConversationTurnState.PERSISTED
                || (state == AiConversationTurnState.INTERRUPTED
                        && (interruptionSource == null
                                || interruptionSource
                                        == AiConversationInterruptionSource.USER_STOP));
    }
}
