package com.example.temperate.service.user.aiconversation.generation.cancellation;

/**
 * 表示取消意图首次写入、重复请求或已终态三种幂等结果。
 */
public record AiConversationGenerationCancellationResult(
        String status,
        String generationPublicId,
        String cancelSource) {
}
