package com.example.temperate.service.user.aiconversation.context;

/**
 * 返回临时会话轮次原子创建结果以及只在成功时有效的单调序号。
 */
public record AiConversationEphemeralStart(
        AiConversationContextWriteOutcome outcome,
        long ordinal) {

    public static AiConversationEphemeralStart applied(long ordinal) {
        return new AiConversationEphemeralStart(
                AiConversationContextWriteOutcome.APPLIED, ordinal);
    }

    public static AiConversationEphemeralStart failed(
            AiConversationContextWriteOutcome outcome) {
        return new AiConversationEphemeralStart(outcome, 0L);
    }
}
