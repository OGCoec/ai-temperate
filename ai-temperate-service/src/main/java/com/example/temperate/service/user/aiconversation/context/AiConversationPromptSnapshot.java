package com.example.temperate.service.user.aiconversation.context;

import java.util.List;

/**
 * 表示发送给上游前已经冻结且完成 Token 预算校验的不可变 Prompt 快照。
 */
public record AiConversationPromptSnapshot(
        String systemPrompt,
        String durableCompactionJson,
        String ephemeralCompactionJson,
        List<AiConversationTurn> historicalTurns,
        AiConversationContent currentInput,
        String generation,
        long estimatedPromptTokens,
        boolean shouldCompactAfterCompletion) {

    public AiConversationPromptSnapshot {
        historicalTurns = historicalTurns == null
                ? List.of()
                : List.copyOf(historicalTurns);
    }
}
