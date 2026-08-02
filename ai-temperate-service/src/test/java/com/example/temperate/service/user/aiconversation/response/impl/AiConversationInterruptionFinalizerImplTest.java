package com.example.temperate.service.user.aiconversation.response.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleDiagnosticService;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationInterruptionCommand;
import com.example.temperate.service.user.aiconversation.response.AiConversationRequestLifecycle;
import com.example.temperate.service.user.aiconversation.response.AiConversationRequestState;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * 验证取消结算执行器即使队列拒绝任务，也会在有限同步兜底中完成系统失败全额退款。
 */
final class AiConversationInterruptionFinalizerImplTest {

    @Test
    void queueRejectionFallsBackToSynchronousFullRefund() {
        AiConversationSettlementService settlementService =
                mock(AiConversationSettlementService.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("full");
        };
        AiConversationLifecycleDiagnosticService diagnostics =
                mock(AiConversationLifecycleDiagnosticService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(diagnostics).withContext(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        AiConversationInterruptionFinalizerImpl finalizer =
                new AiConversationInterruptionFinalizerImpl(
                        settlementService,
                        rejectingExecutor,
                        new AiConversationMetrics(new SimpleMeterRegistry()),
                        diagnostics);
        AiConversationRequestLifecycle lifecycle = interruptedLifecycle();
        byte[] usageId = new byte[] {1};

        finalizer.submit(
                new AiConversationInterruptionCommand(
                        usageId,
                        null,
                        AiConversationTerminalBillingAction.REFUND_FULL,
                        "AI_UPSTREAM_TIMEOUT"),
                lifecycle);

        verify(settlementService).refundFailed(
                usageId, "AI_UPSTREAM_TIMEOUT");
        assertThat(lifecycle.state())
                .isEqualTo(AiConversationRequestState.FAILED_REFUNDED);
    }

    private static AiConversationRequestLifecycle interruptedLifecycle() {
        AiConversationRequestLifecycle lifecycle =
                new AiConversationRequestLifecycle();
        lifecycle.markReserved();
        lifecycle.markConnecting();
        assertThat(lifecycle.tryBeginInterruptedFinalization()).isTrue();
        return lifecycle;
    }
}
