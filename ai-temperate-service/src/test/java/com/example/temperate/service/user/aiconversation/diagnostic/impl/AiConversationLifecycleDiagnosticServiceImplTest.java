package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.config.AiConversationLifecycleDiagnosticsProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 验证生命周期诊断上下文在线程池任务边界内显式传播，并在任务结束后完整恢复调用线程 MDC。
 */
final class AiConversationLifecycleDiagnosticServiceImplTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void enabledScopePropagatesAndRestoresMdc() {
        AiConversationLifecycleDiagnosticServiceImpl diagnostics =
                new AiConversationLifecycleDiagnosticServiceImpl(
                        new AiConversationLifecycleDiagnosticsProperties(
                                true, 1.0d));
        AiConversationLifecycleTraceContext context = context();
        MDC.put("traceId", "outer-trace");
        AtomicReference<String> observedTrace = new AtomicReference<>();
        AtomicReference<String> observedUsage = new AtomicReference<>();

        diagnostics.withContext(context, () -> {
            observedTrace.set(MDC.get("traceId"));
            observedUsage.set(MDC.get(
                    AiConversationLifecycleDiagnosticServiceImpl.USAGE_MDC_KEY));
            assertThat(diagnostics.currentContext()).isEqualTo(context);
        });

        assertThat(observedTrace).hasValue(context.traceId());
        assertThat(observedUsage).hasValue(context.usagePublicId());
        assertThat(MDC.get("traceId")).isEqualTo("outer-trace");
        assertThat(MDC.get(
                AiConversationLifecycleDiagnosticServiceImpl.USAGE_MDC_KEY))
                .isNull();
        assertThat(diagnostics.currentContext())
                .isEqualTo(AiConversationLifecycleTraceContext.unavailable());
    }

    @Test
    void disabledScopeDoesNotMutateMdc() {
        AiConversationLifecycleDiagnosticServiceImpl diagnostics =
                new AiConversationLifecycleDiagnosticServiceImpl(
                        new AiConversationLifecycleDiagnosticsProperties(
                                false, 0.0d));
        MDC.put("traceId", "outer-trace");

        diagnostics.withContext(context(), () ->
                assertThat(MDC.get("traceId")).isEqualTo("outer-trace"));

        assertThat(MDC.get("traceId")).isEqualTo("outer-trace");
        assertThat(diagnostics.currentContext())
                .isEqualTo(AiConversationLifecycleTraceContext.unavailable());
    }

    private static AiConversationLifecycleTraceContext context() {
        return new AiConversationLifecycleTraceContext(
                "4b8f6f5d-27ae-40c5-8f4a-75875356b6f3",
                "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6",
                "AZ-50wCZAQGBuCvbSqIYsA",
                "AZ-50wCZAQGBuCvbSqIYtQ",
                "A-model-public-id",
                System.nanoTime());
    }
}
