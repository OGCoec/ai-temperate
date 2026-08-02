package com.example.temperate.service.user.aiconversation.generation;

/**
 * 表示唯一终态 CAS 是否获胜，以及获胜后供 RabbitMQ 发布的公共标识和终态版本。
 */
public record AiConversationGenerationTerminalResult(
        boolean claimed,
        String generationPublicId,
        String usagePublicId,
        String terminalType,
        String terminalReason,
        int terminalVersion) {
}
