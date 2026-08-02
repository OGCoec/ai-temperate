package com.example.temperate.service.user.aiconversation.response.impl;

import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingAction;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingDecision;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 将系统责任失败统一映射为全额退款，并为客户端部分取消生成保守且可审计的本地用量估算。
 *
 * <p>估算只用于用户主动取消且上游没有最终 Usage 的场景；系统超时、限流和断流即使已经展示部分文本
 * 也必须全退，不能把平台故障成本转嫁给用户。</p>
 */
@Service
public final class AiConversationTerminalBillingPolicyImpl
        implements AiConversationTerminalBillingPolicy {

    @Override
    public AiConversationTerminalBillingDecision systemFailure(
            Throwable failure) {
        String failureCode = failure instanceof AiConversationException exception
                ? exception.code().name()
                : AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED.name();
        return new AiConversationTerminalBillingDecision(
                AiConversationTerminalBillingAction.REFUND_FULL,
                null,
                failureCode);
    }

    @Override
    public AiConversationTerminalBillingDecision clientCancellation(
            AiConversationReservation reservation,
            AiConversationUsage reportedUsage,
            String deliveredAssistantText) {
        Objects.requireNonNull(reservation);
        if (reportedUsage != null) {
            return new AiConversationTerminalBillingDecision(
                    AiConversationTerminalBillingAction.SETTLE_REPORTED_USAGE,
                    reportedUsage,
                    "CLIENT_CANCELLED_WITH_REPORTED_USAGE");
        }
        if (deliveredAssistantText == null
                || deliveredAssistantText.isEmpty()) {
            return new AiConversationTerminalBillingDecision(
                    AiConversationTerminalBillingAction.REFUND_FULL,
                    null,
                    "CLIENT_CANCELLED_WITHOUT_OUTPUT");
        }
        // 本地估算沿用上下文估算的 UTF-8 三字节折合一个 Token 规则，并始终向上取整；
        // 缓存与思考 Token 无法可靠恢复时记零，避免凭空扩大用户费用。
        long completionTokens = estimatedTextTokens(deliveredAssistantText);
        AiConversationUsage estimated = new AiConversationUsage(
                reservation.estimatedPromptTokens(),
                0L,
                completionTokens,
                0L);
        return new AiConversationTerminalBillingDecision(
                AiConversationTerminalBillingAction
                        .SETTLE_ESTIMATED_CLIENT_CANCEL,
                estimated,
                "CLIENT_CANCELLED_ESTIMATED");
    }

    private static long estimatedTextTokens(String value) {
        long bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1L, Math.floorDiv(Math.addExact(bytes, 2L), 3L));
    }
}
