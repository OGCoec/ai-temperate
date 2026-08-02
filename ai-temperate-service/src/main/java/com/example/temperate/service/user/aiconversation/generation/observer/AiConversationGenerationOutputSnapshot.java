package com.example.temperate.service.user.aiconversation.generation.observer;

/**
 * 表示重连时从 Redis 分块快照恢复的单调 revision、完整部分文本和可选终态事件。
 */
public record AiConversationGenerationOutputSnapshot(
        long revision,
        String assistantText,
        String terminalEventName,
        String terminalDataJson) {
}
