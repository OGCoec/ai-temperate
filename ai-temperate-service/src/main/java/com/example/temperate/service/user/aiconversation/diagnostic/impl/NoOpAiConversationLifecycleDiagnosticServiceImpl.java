package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleEvent;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在生命周期诊断关闭时提供无状态空实现，确保日志、MDC 和异步业务行为均保持原状。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.lifecycle-diagnostics",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public final class NoOpAiConversationLifecycleDiagnosticServiceImpl
        implements AiConversationLifecycleDiagnosticService {

    @Override
    public void record(
            AiConversationLifecycleTraceContext context,
            String phase,
            AiConversationLifecycleEvent details) {
        // 关闭态禁止创建日志字段或采样窗口。
    }

    @Override
    public AiConversationLifecycleTraceContext currentContext() {
        return AiConversationLifecycleTraceContext.unavailable();
    }

    @Override
    public <T> T withContext(
            AiConversationLifecycleTraceContext context,
            Supplier<T> action) {
        return Objects.requireNonNull(action).get();
    }

    @Override
    public void withContext(
            AiConversationLifecycleTraceContext context,
            Runnable action) {
        Objects.requireNonNull(action).run();
    }
}
