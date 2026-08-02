package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示服务层产生的具名 SSE 事件和结构化数据，Web 层只负责协议编码与响应头。
 */
public record AiConversationStreamEvent(String name, Object data) {

    public static AiConversationStreamEvent accepted(
            AiConversationAcceptedData data) {
        return new AiConversationStreamEvent("accepted", data);
    }

    public static AiConversationStreamEvent delta(
            AiConversationDeltaData data) {
        return new AiConversationStreamEvent("delta", data);
    }

    public static AiConversationStreamEvent activity(
            AiConversationActivityData data) {
        return new AiConversationStreamEvent("activity", data);
    }

    public static AiConversationStreamEvent source(
            AiConversationSourceData data) {
        return new AiConversationStreamEvent("source", data);
    }

    public static AiConversationStreamEvent reasoningSummary(
            AiConversationReasoningSummaryData data) {
        return new AiConversationStreamEvent("reasoning_summary", data);
    }

    public static AiConversationStreamEvent heartbeat() {
        return new AiConversationStreamEvent(
                "heartbeat", new AiConversationHeartbeatData("alive"));
    }

    public static AiConversationStreamEvent completed(
            AiConversationCompletedData data) {
        return new AiConversationStreamEvent("completed", data);
    }

    public static AiConversationStreamEvent error(
            AiConversationErrorData data) {
        return new AiConversationStreamEvent("error", data);
    }
}
