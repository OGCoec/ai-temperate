package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.function.Supplier;

/**
 * 统一输出 AI 会话生命周期事件，并在受控同步作用域内传播异步结算所需的脱敏 Trace 上下文。
 */
public interface AiConversationLifecycleDiagnosticService {

    void record(
            AiConversationLifecycleTraceContext context,
            String phase,
            AiConversationLifecycleEvent details);

    default void record(
            AiConversationLifecycleTraceContext context,
            String phase) {
        record(context, phase, AiConversationLifecycleEvent.empty());
    }

    AiConversationLifecycleTraceContext currentContext();

    <T> T withContext(
            AiConversationLifecycleTraceContext context,
            Supplier<T> action);

    void withContext(
            AiConversationLifecycleTraceContext context,
            Runnable action);
}
