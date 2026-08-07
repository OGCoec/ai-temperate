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
        AiConversationInterruptionSource interruptionSource,
        long estimatedTokens) {

    public AiConversationTurn(
            String reference,
            Long messageId,
            Long ordinal,
            AiConversationContent user,
            AiConversationContent assistant,
            AiConversationTurnState state) {
        this(reference, messageId, ordinal, user, assistant, state, null, 0L);
    }

    public AiConversationTurn(
            String reference,
            Long messageId,
            Long ordinal,
            AiConversationContent user,
            AiConversationContent assistant,
            AiConversationTurnState state,
            AiConversationInterruptionSource interruptionSource) {
        this(reference, messageId, ordinal, user, assistant, state,
                interruptionSource, 0L);
    }

    public AiConversationTurn {
        if (estimatedTokens < 0L) {
            throw new IllegalArgumentException(
                    "AI conversation turn token estimate must not be negative.");
        }
    }

    public boolean ephemeral() {
        return state != AiConversationTurnState.PERSISTED;
    }

    /**
     * 只有用户明确停止的草稿允许进入下一次模型上下文；v2 缓存不再把缺失来源解释为用户意图。
     */
    public boolean includedInPrompt() {
        return state == AiConversationTurnState.PERSISTED
                || (state == AiConversationTurnState.INTERRUPTED
                        && interruptionSource
                                == AiConversationInterruptionSource.USER_STOP);
    }
}
