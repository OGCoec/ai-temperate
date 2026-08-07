package com.example.temperate.service.user.aiconversation.compaction;

/**
 * 表示 Redis 单飞压缩任务是本次新建还是复用了同一上下文版本的已有任务。
 */
public record AiConversationCompactionClaim(
        AiConversationCompactionOperation operation,
        boolean created) {
}
