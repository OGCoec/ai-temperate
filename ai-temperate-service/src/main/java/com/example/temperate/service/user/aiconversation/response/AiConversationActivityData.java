package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示同一条下游 SSE 中可公开的模型活动状态，只包含经过白名单标准化的阶段和真实上游 query。
 * eventId 绑定精确业务身份且不包含 sequence 和 occurredAt，供前端在重连或上游重放时防御性去重。
 */
public record AiConversationActivityData(
        long sequence,
        String eventId,
        String activityId,
        String phase,
        String status,
        String query,
        String occurredAt) {
}
