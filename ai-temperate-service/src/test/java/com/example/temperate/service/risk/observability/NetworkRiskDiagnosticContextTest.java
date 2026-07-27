package com.example.temperate.service.risk.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * 验证网络风险诊断上下文只在当前执行线程和显式作用域内可见，并在嵌套作用域结束后恢复原值。
 */
class NetworkRiskDiagnosticContextTest {

    @Test
    void restoresNestedContextAndClearsOuterScope() {
        assertThat(NetworkRiskDiagnosticContext.current().traceId()).isEqualTo("absent");

        try (NetworkRiskDiagnosticContext.Scope ignored =
                NetworkRiskDiagnosticContext.open(
                        "trace-outer", 1, "REQUEST", "network_risk_prehandle")) {
            assertThat(NetworkRiskDiagnosticContext.current())
                    .extracting(
                            NetworkRiskDiagnosticContext.Snapshot::traceId,
                            NetworkRiskDiagnosticContext.Snapshot::invocationNo,
                            NetworkRiskDiagnosticContext.Snapshot::dispatcherType,
                            NetworkRiskDiagnosticContext.Snapshot::phase)
                    .containsExactly(
                            "trace-outer", 1, "REQUEST", "network_risk_prehandle");

            try (NetworkRiskDiagnosticContext.Scope nested =
                    NetworkRiskDiagnosticContext.open(
                            "trace-inner", 2, "ASYNC", "network_risk_prehandle")) {
                assertThat(NetworkRiskDiagnosticContext.current().traceId())
                        .isEqualTo("trace-inner");
            }

            assertThat(NetworkRiskDiagnosticContext.current().traceId())
                    .isEqualTo("trace-outer");
        }

        assertThat(NetworkRiskDiagnosticContext.current().traceId()).isEqualTo("absent");
    }

    @Test
    void callPropagatesCapturedCorrelationToAnotherThreadWithoutLeaking() {
        NetworkRiskDiagnosticContext.Snapshot snapshot =
                NetworkRiskDiagnosticContext.snapshot(
                        "trace-reactive", 1, "REQUEST", "admin_login_promotion");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            NetworkRiskDiagnosticContext.Snapshot observed =
                    CompletableFuture.supplyAsync(
                                    () -> NetworkRiskDiagnosticContext.call(
                                            snapshot,
                                            NetworkRiskDiagnosticContext::current),
                                    executor)
                            .join();

            assertThat(observed.traceId()).isEqualTo("trace-reactive");
            assertThat(observed.phase()).isEqualTo("admin_login_promotion");
            assertThat(CompletableFuture.supplyAsync(
                            () -> NetworkRiskDiagnosticContext.current().traceId(),
                            executor)
                    .join()).isEqualTo("absent");
        } finally {
            executor.shutdownNow();
        }
    }
}
