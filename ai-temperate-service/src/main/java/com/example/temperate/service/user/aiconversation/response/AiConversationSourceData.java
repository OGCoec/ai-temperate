package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示上游明确返回且通过 HTTP(S) 校验的研究来源，不承载网页正文或供应商原始对象。
 */
public record AiConversationSourceData(
        long sequence,
        String activityId,
        String sourceId,
        String title,
        String url,
        String domain,
        String role,
        String occurredAt) {
}
