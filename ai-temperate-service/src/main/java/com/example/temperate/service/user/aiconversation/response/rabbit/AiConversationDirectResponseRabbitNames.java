package com.example.temperate.service.user.aiconversation.response.rabbit;

/**
 * 集中定义直接 SSE 跨实例 Stop 控制消息的 Exchange、实例队列、路由键和死信资源。
 */
public final class AiConversationDirectResponseRabbitNames {

    public static final String CONTROL_EXCHANGE = "ait.ai.response.control.v1";
    public static final String DEAD_LETTER_EXCHANGE = "ait.ai.response.control.dlx.v1";
    public static final String DEAD_LETTER_QUEUE = "ait.ai.response.control.dead.v1";
    public static final String DEAD_LETTER_ROUTING_KEY = "response.control.dead";

    private AiConversationDirectResponseRabbitNames() {
    }

    public static String controlQueue(String instanceId) {
        return "ait.ai.response.control.v1." + safeInstanceId(instanceId);
    }

    public static String controlRoutingKey(String instanceId) {
        return "response.control." + safeInstanceId(instanceId);
    }

    private static String safeInstanceId(String instanceId) {
        if (instanceId == null
                || !instanceId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException(
                    "AI direct response instance ID is invalid.");
        }
        return instanceId;
    }
}
