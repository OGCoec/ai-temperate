package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示供应商明确公开的推理摘要增量，不是隐藏思维链或逐 Token 推理内容。
 */
public record AiConversationReasoningSummaryData(
        long sequence,
        String activityId,
        String textDelta,
        String occurredAt) {
}
