package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.Map;

/**
 * 记录 AI 流式传输跨边界的结构化时间点，连接 Worker、Redis、Servlet 写出和观察者接收阶段。
 * 该接口只接收脱敏元数据，不负责记录模型正文或改变流式业务语义。
 */
@FunctionalInterface
public interface AiConversationStreamTransportDiagnosticService {

    void record(
            AiConversationStreamTimingContext context,
            String event,
            Map<String, ?> details);

    /**
     * 以失败开放方式写入诊断信息，确保日志净化器或 Appender 的运行时异常不会改变 Reactor 主链路的业务结果。
     * 这里禁止再次写日志，避免故障中的诊断通道形成递归调用。
     */
    default void recordSafely(
            AiConversationStreamTimingContext context,
            String event,
            Map<String, ?> details) {
        try {
            record(context, event, details);
        } catch (RuntimeException ignored) {
            // 诊断属于旁路观测能力，失败时只能丢弃本条记录，不能中断模型流或浏览器 SSE。
        }
    }

    static AiConversationStreamTransportDiagnosticService noOp() {
        return (context, event, details) -> {
        };
    }
}
