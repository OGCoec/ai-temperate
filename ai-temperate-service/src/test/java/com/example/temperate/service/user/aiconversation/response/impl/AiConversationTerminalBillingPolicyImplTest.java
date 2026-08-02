package com.example.temperate.service.user.aiconversation.response.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingAction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * 验证系统失败全退以及客户端取消按输出情况选择退款或估算结算的终态策略。
 */
final class AiConversationTerminalBillingPolicyImplTest {

    private final AiConversationTerminalBillingPolicyImpl policy =
            new AiConversationTerminalBillingPolicyImpl();

    @Test
    void systemFailureAlwaysRequestsFullRefundEvenAfterPartialOutput() {
        AiConversationException failure = new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型响应未能完成",
                true);

        var decision = policy.systemFailure(failure);

        assertThat(decision.action())
                .isEqualTo(AiConversationTerminalBillingAction.REFUND_FULL);
        assertThat(decision.failureCode())
                .isEqualTo("AI_UPSTREAM_STREAM_FAILED");
        assertThat(decision.usage()).isNull();
    }

    @Test
    void everyControlledUpstreamFailureKeepsItsSafeRefundCode() {
        for (AiConversationErrorCode code : List.of(
                AiConversationErrorCode.AI_UPSTREAM_TIMEOUT,
                AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                AiConversationErrorCode.AI_USAGE_UNAVAILABLE)) {
            var decision = policy.systemFailure(new AiConversationException(
                    code,
                    "不应进入生命周期日志的第三方错误说明",
                    true));

            assertThat(decision.action())
                    .isEqualTo(AiConversationTerminalBillingAction.REFUND_FULL);
            assertThat(decision.failureCode()).isEqualTo(code.name());
        }
    }

    @Test
    void localBackpressureOverflowRequestsFullRefund() {
        AiConversationException failure = new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型响应未能完成",
                true,
                AiConversationStreamFailureReason.STREAM_BACKPRESSURE_OVERFLOW,
                Exceptions.failWithOverflow());

        var decision = policy.systemFailure(failure);

        assertThat(decision.action())
                .isEqualTo(AiConversationTerminalBillingAction.REFUND_FULL);
        assertThat(decision.failureCode())
                .isEqualTo("AI_UPSTREAM_STREAM_FAILED");
        assertThat(decision.usage()).isNull();
    }

    @Test
    void clientCancellationWithoutOutputRequestsFullRefund() {
        var decision = policy.clientCancellation(
                reservation(57L), null, "");

        assertThat(decision.action())
                .isEqualTo(AiConversationTerminalBillingAction.REFUND_FULL);
        assertThat(decision.failureCode())
                .isEqualTo("CLIENT_CANCELLED_WITHOUT_OUTPUT");
    }

    @Test
    void clientCancellationWithPartialOutputUsesConservativeEstimatedUsage() {
        var decision = policy.clientCancellation(
                reservation(57L), null, "你好");

        assertThat(decision.action()).isEqualTo(
                AiConversationTerminalBillingAction
                        .SETTLE_ESTIMATED_CLIENT_CANCEL);
        assertThat(decision.failureCode())
                .isEqualTo("CLIENT_CANCELLED_ESTIMATED");
        assertThat(decision.usage()).isEqualTo(
                new AiConversationUsage(57L, 0L, 2L, 0L));
    }

    @Test
    void clientCancellationWithReportedUsageSettlesReportedUsage() {
        AiConversationUsage usage = new AiConversationUsage(32, 0, 13, 0);

        var decision = policy.clientCancellation(
                reservation(57L), usage, "partial");

        assertThat(decision.action()).isEqualTo(
                AiConversationTerminalBillingAction.SETTLE_REPORTED_USAGE);
        assertThat(decision.usage()).isEqualTo(usage);
        assertThat(decision.failureCode())
                .isEqualTo("CLIENT_CANCELLED_WITH_REPORTED_USAGE");
    }

    private static AiConversationReservation reservation(long promptTokens) {
        return new AiConversationReservation(
                new byte[16],
                new byte[16],
                null,
                0,
                241L,
                promptTokens,
                128_000L,
                new BigDecimal("0.75"),
                new BigDecimal("0.075"),
                new BigDecimal("4.5"),
                true,
                false);
    }
}
