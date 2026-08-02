package com.example.temperate.service.user.aiconversation.response.impl;

import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementResult;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleEvent;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTimed;
import com.example.temperate.service.user.aiconversation.response.AiConversationInterruptionCommand;
import com.example.temperate.service.user.aiconversation.response.AiConversationInterruptionFinalizer;
import com.example.temperate.service.user.aiconversation.response.AiConversationRequestLifecycle;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingAction;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 在专用有界线程池中执行取消结算或系统失败退款，防止 Reactor 取消线程阻塞或裸订阅吞掉数据库异常。
 */
@Service
public final class AiConversationInterruptionFinalizerImpl
        implements AiConversationInterruptionFinalizer {

    private static final int MAX_ATTEMPTS = 3;

    private final AiConversationSettlementService settlementService;
    private final Executor executor;
    private final AiConversationMetrics metrics;
    private final AiConversationLifecycleDiagnosticService diagnostics;

    public AiConversationInterruptionFinalizerImpl(
            AiConversationSettlementService settlementService,
            @Qualifier("aiConversationFinalizerExecutor") Executor executor,
            AiConversationMetrics metrics,
            AiConversationLifecycleDiagnosticService diagnostics) {
        this.settlementService = Objects.requireNonNull(settlementService);
        this.executor = Objects.requireNonNull(executor);
        this.metrics = Objects.requireNonNull(metrics);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    @AiConversationLifecycleTimed(stage = "INTERRUPTION_FINALIZER_SUBMIT")
    public void submit(
            AiConversationInterruptionCommand command,
            AiConversationRequestLifecycle lifecycle) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(lifecycle);
        long submittedNanos = System.nanoTime();
        diagnostics.record(command.traceContext(), "FINALIZER_SUBMITTED");
        try {
            executor.execute(() -> diagnostics.withContext(
                    command.traceContext(),
                    () -> {
                        diagnostics.record(
                                command.traceContext(),
                                "FINALIZER_STARTED",
                                AiConversationLifecycleEvent.execution(
                                        null,
                                        elapsedMillis(submittedNanos)));
                        finalizeWithLimit(command, lifecycle);
                    }));
        } catch (RejectedExecutionException rejected) {
            // 队列饱和属于服务端容量问题，必须在当前受控调用栈执行有限短事务，不能静默遗留预扣。
            diagnostics.record(
                    command.traceContext(),
                    "FINALIZER_REJECTED_SYNC_FALLBACK",
                    AiConversationLifecycleEvent.execution(
                            null,
                            elapsedMillis(submittedNanos)));
            diagnostics.withContext(
                    command.traceContext(),
                    () -> finalizeWithLimit(command, lifecycle));
        }
    }

    private void finalizeWithLimit(
            AiConversationInterruptionCommand command,
            AiConversationRequestLifecycle lifecycle) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                diagnostics.record(
                        command.traceContext(),
                        "SETTLEMENT_RETRY_STARTED",
                        AiConversationLifecycleEvent.execution(attempt, null));
            }
            try {
                if (command.action()
                        == AiConversationTerminalBillingAction
                                .SETTLE_REPORTED_USAGE
                        || command.action()
                        == AiConversationTerminalBillingAction
                                .SETTLE_ESTIMATED_CLIENT_CANCEL) {
                    AiConversationSettlementResult result =
                            settlementService.settleInterrupted(
                                    command.settlement());
                    if (result.requiresReconciliation()) {
                        lifecycle.markReconcileRequired();
                        metrics.request("reconcile");
                    } else {
                        lifecycle.markSettled();
                        metrics.request("interrupted");
                    }
                } else if (command.action()
                        == AiConversationTerminalBillingAction.REFUND_FULL) {
                    settlementService.refundFailed(
                            command.usageId(), command.failureCode());
                    lifecycle.markFailedRefunded();
                    metrics.request("failed");
                } else {
                    settlementService.markReconcileRequired(
                            command.usageId(), command.failureCode());
                    lifecycle.markReconcileRequired();
                    metrics.request("reconcile");
                }
                diagnostics.record(
                        command.traceContext(),
                        "FINALIZER_COMPLETED",
                        AiConversationLifecycleEvent.terminal(
                                lifecycle.state().name(),
                                "CANCEL",
                                command.settlement() == null
                                        ? "UPSTREAM_FAILED"
                                        : command.settlement().finishReason(),
                                command.failureCode(),
                                command.action().name(),
                                lifecycle.state().name(),
                                command.settlement() != null
                                        && !command.settlement().assistant().text().isEmpty(),
                                command.settlement() != null
                                        && command.action()
                                        == AiConversationTerminalBillingAction
                                                .SETTLE_REPORTED_USAGE,
                                command.settlement() == null
                                        ? 0L
                                        : command.settlement().assistant().text().length()));
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
        }
        try {
            settlementService.markReconcileRequired(
                    command.usageId(), command.failureCode());
        } catch (RuntimeException ignored) {
            // 数据库持续不可用时保持 RESERVED，后续过期扫描负责识别，禁止无限重试。
        }
        lifecycle.markReconcileRequired();
        metrics.request("reconcile");
        diagnostics.record(
                command.traceContext(),
                "RECONCILE_REQUIRED_MARKED",
                AiConversationLifecycleEvent.terminal(
                        "RECONCILE_REQUIRED",
                        "CANCEL",
                        "INTERRUPTED",
                        command.failureCode(),
                        command.action().name(),
                        "RECONCILE_REQUIRED",
                        false,
                        false,
                        0L));
        if (lastFailure == null) {
            throw new IllegalStateException(
                    "AI interruption finalization failed without a cause.");
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
