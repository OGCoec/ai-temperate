package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementCommand;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import java.util.Objects;

/**
 * 承载客户端取消或流异常后的有限异步结算参数，不包含任何需要长期保存的流式对象。
 */
public record AiConversationInterruptionCommand(
        byte[] usageId,
        AiConversationSettlementCommand settlement,
        AiConversationTerminalBillingAction action,
        String failureCode,
        AiConversationLifecycleTraceContext traceContext) {

    public AiConversationInterruptionCommand {
        usageId = usageId.clone();
        Objects.requireNonNull(action);
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException(
                    "AI interruption failure code must not be blank.");
        }
        traceContext = traceContext == null
                ? AiConversationLifecycleTraceContext.unavailable()
                : traceContext;
        if ((action == AiConversationTerminalBillingAction.REFUND_FULL
                || action == AiConversationTerminalBillingAction
                        .RECONCILE_REQUIRED) && settlement != null) {
            throw new IllegalArgumentException(
                    "Refund or reconciliation action cannot carry settlement.");
        }
        boolean settlementRequired = action
                == AiConversationTerminalBillingAction.SETTLE_REPORTED_USAGE
                || action == AiConversationTerminalBillingAction
                        .SETTLE_ESTIMATED_CLIENT_CANCEL;
        if (settlementRequired && settlement == null) {
            throw new IllegalArgumentException(
                    "Settlement action requires settlement details.");
        }
    }

    public AiConversationInterruptionCommand(
            byte[] usageId,
            AiConversationSettlementCommand settlement,
            AiConversationTerminalBillingAction action,
            String failureCode) {
        this(
                usageId,
                settlement,
                action,
                failureCode,
                AiConversationLifecycleTraceContext.unavailable());
    }
}
