package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示单次上游请求内按顺序转发给客户端的文本增量。
 */
public record AiConversationDeltaData(
        long sequence,
        String type,
        String text) {
}
