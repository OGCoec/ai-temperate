package com.example.temperate.service.user.aiconversation.compaction;

import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;

/**
 * 表示压缩请求无需执行，或返回成功入队、复用中的当前任务稳定状态。
 */
public record AiConversationCompactionRequestResult(
        String status,
        AiConversationCompactionOperation operation,
        AiConversationContextUsage usage) {

    public boolean accepted() {
        return operation != null && operation.status().active();
    }
}
