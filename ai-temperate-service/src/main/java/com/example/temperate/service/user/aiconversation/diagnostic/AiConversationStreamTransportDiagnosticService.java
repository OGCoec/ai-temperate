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

    static AiConversationStreamTransportDiagnosticService noOp() {
        return (context, event, details) -> {
        };
    }
}
