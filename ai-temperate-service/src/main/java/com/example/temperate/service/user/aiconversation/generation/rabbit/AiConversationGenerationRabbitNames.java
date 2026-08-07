package com.example.temperate.service.user.aiconversation.generation.rabbit;

/**
 * 集中定义异步生成、控制、失联检查、事实终态及死信 RabbitMQ 资源名称和路由规则。
 */
public final class AiConversationGenerationRabbitNames {

    public static final String GENERATION_EXCHANGE = "ait.ai.generation.v1";
    public static final String CONTROL_EXCHANGE = "ait.ai.generation.control.v1";
    public static final String DETACH_EXCHANGE = "ait.ai.generation.detach.v1";
    public static final String TERMINAL_EXCHANGE = "ait.ai.generation.terminal.v1";
    public static final String DEAD_LETTER_EXCHANGE = "ait.ai.generation.dlx.v1";
    public static final String GENERATION_QUEUE = "ait.ai.generation.worker.v1";
    public static final String DETACH_QUEUE = "ait.ai.generation.detach-check.v1";
    public static final String TERMINAL_QUEUE = "ait.ai.generation.billing.v1";
    public static final String TERMINAL_QUEUE_V2 = "ait.ai.generation.billing.v2";
    public static final String DEAD_LETTER_QUEUE = "ait.ai.generation.dead.v1";
    public static final String GENERATION_ROUTING_KEY = "generation.requested";
    public static final String DETACH_ROUTING_KEY = "generation.detach-check";
    public static final String TERMINAL_ROUTING_KEY = "generation.terminated";
    public static final String TERMINAL_ROUTING_KEY_V2 = "generation.terminated.v2";
    public static final String DEAD_LETTER_ROUTING_KEY = "generation.dead";

    private AiConversationGenerationRabbitNames() {
    }

    public static String controlQueue(String instanceId) {
        return "ait.ai.generation.control.v1." + safeInstanceId(instanceId);
    }

    public static String workerQueueV2(String instanceId) {
        return "ait.ai.generation.worker.v2." + safeInstanceId(instanceId);
    }

    public static String workerRoutingKeyV2(String instanceId) {
        return "generation.requested.v2." + safeInstanceId(instanceId);
    }

    public static String controlRoutingKey(String instanceId) {
        return "generation.control." + safeInstanceId(instanceId);
    }

    private static String safeInstanceId(String instanceId) {
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("AI conversation instance ID is invalid.");
        }
        return instanceId;
    }
}
