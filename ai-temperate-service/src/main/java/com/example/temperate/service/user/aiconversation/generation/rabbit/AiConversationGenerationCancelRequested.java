package com.example.temperate.service.user.aiconversation.generation.rabbit;

/**
 * 通知任务 Owner 应用已经持久化的取消意图，消息本身不能决定资金处理。
 */
public record AiConversationGenerationCancelRequested(
        String generationPublicId,
        String cancelSource,
        int cancelVersion) {
}
