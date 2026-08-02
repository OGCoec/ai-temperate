package com.example.temperate.service.user.aiconversation.generation.rabbit;

/**
 * 表示 Rabbit 消息与 PostgreSQL 权威 Generation 证据冲突，消费者必须拒绝进入 DLQ 且不得修改资金状态。
 */
public final class AiConversationGenerationMessageRejectedException
        extends RuntimeException {

    public AiConversationGenerationMessageRejectedException(String message) {
        super(message);
    }
}
