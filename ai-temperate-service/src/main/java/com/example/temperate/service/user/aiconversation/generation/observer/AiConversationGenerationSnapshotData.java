package com.example.temperate.service.user.aiconversation.generation.observer;

/**
 * 向重连 SSE 发送当前单调 revision 和完整部分回答，客户端据此替换旧本地草稿而非重复拼接。
 */
public record AiConversationGenerationSnapshotData(
        long revision,
        String text) {
}
