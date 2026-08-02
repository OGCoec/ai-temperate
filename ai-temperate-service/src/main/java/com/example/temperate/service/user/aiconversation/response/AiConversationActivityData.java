package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示同一条下游 SSE 中可公开的模型活动状态，只包含经过白名单标准化的阶段和真实上游 query。
 */
public record AiConversationActivityData(
        long sequence,
        String activityId,
        String phase,
        String status,
        String query,
        String occurredAt) {
}
