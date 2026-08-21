package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 该单元测试是来约束会员支付调度 Trace 在当前轮次内可用，并在关闭上下文后恢复线程原有诊断状态。
 */
final class MembershipPaymentTraceContextTest {

    @AfterEach
    void clearLoggingContext() {
        MDC.clear();
    }

    @Test
    void generatesTraceForSchedulerAndRemovesItAfterClose() {
        String generated;
        try (MembershipPaymentTraceContext context =
                MembershipPaymentTraceContext.open()) {
            generated = context.traceId();
            assertThat(generated).matches("[A-Za-z0-9_-]{1,128}");
            assertThat(MembershipPaymentTraceContext.currentTraceId())
                    .isEqualTo(generated);
        }

        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void reusesAndPreservesSafeExistingTrace() {
        MDC.put("traceId", "existing-trace");

        try (MembershipPaymentTraceContext context =
                MembershipPaymentTraceContext.open()) {
            assertThat(context.traceId()).isEqualTo("existing-trace");
        }

        assertThat(MDC.get("traceId")).isEqualTo("existing-trace");
    }
}
