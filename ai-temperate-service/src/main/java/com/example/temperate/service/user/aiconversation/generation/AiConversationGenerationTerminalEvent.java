package com.example.temperate.service.user.aiconversation.generation;

/**
 * 表示唯一终态数据库事务已经提交，可安全发布事实终态消息供 Billing Consumer 读取。
 */
public record AiConversationGenerationTerminalEvent(
        String generationPublicId,
        String usagePublicId,
        String terminalType,
        String terminalReason,
        int terminalVersion,
        String traceId) {
}
