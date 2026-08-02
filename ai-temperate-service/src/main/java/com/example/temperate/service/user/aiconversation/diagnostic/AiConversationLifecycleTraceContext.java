package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.Objects;

/**
 * 承载一次 AI 会话从 Web 请求到异步终态结算所需的脱敏关联标识和本进程计时原点。
 */
public record AiConversationLifecycleTraceContext(
        String traceId,
        String clientRequestId,
        String usagePublicId,
        String conversationPublicId,
        String modelPublicId,
        long requestStartedNanos) {

    private static final String UNAVAILABLE = "unavailable";

    public AiConversationLifecycleTraceContext {
        traceId = normalized(traceId);
        clientRequestId = normalized(clientRequestId);
        usagePublicId = normalized(usagePublicId);
        conversationPublicId = normalized(conversationPublicId);
        modelPublicId = normalized(modelPublicId);
        requestStartedNanos = Math.max(0L, requestStartedNanos);
    }

    /**
     * 创建没有请求关联的安全占位上下文，供后台补偿或诊断关闭路径使用。
     *
     * @return 不包含内部 ID 或敏感信息的占位上下文
     */
    public static AiConversationLifecycleTraceContext unavailable() {
        return new AiConversationLifecycleTraceContext(
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                UNAVAILABLE,
                0L);
    }

    /**
     * 在预扣完成后绑定公共 Usage 与会话 ID，保留原始 Trace 和单调计时原点。
     *
     * @param usagePublicId Usage 公共 ID
     * @param conversationPublicId 会话公共 ID
     * @return 绑定业务公共标识后的新上下文
     */
    public AiConversationLifecycleTraceContext withBusinessCorrelation(
            String usagePublicId,
            String conversationPublicId) {
        return new AiConversationLifecycleTraceContext(
                traceId,
                clientRequestId,
                usagePublicId,
                conversationPublicId,
                modelPublicId,
                requestStartedNanos);
    }

    /**
     * 只在模型公共 ID 已完成解码和存在性校验后绑定，避免把未验证请求字段写入日志。
     *
     * @param modelPublicId 已验证的模型公共 ID
     * @return 绑定模型公共 ID 后的新上下文
     */
    public AiConversationLifecycleTraceContext withModelPublicId(
            String modelPublicId) {
        return new AiConversationLifecycleTraceContext(
                traceId,
                clientRequestId,
                usagePublicId,
                conversationPublicId,
                modelPublicId,
                requestStartedNanos);
    }

    private static String normalized(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.isEmpty() ? UNAVAILABLE : normalized;
    }
}
