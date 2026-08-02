package com.example.temperate.service.user.aiconversation.generation.cancellation;

/**
 * 表示第一次取消意图已经提交，可按 Owner 实例定向发布控制命令。
 */
public record AiConversationGenerationCancellationEvent(
        String generationPublicId,
        String cancelSource,
        int cancelVersion,
        String ownerInstanceId,
        String traceId) {
}
