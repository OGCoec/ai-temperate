package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import java.util.Objects;

/**
 * 承载一次终止事件的计费动作、可选用量和稳定失败码，供同步与异步终态执行器共同消费。
 */
public record AiConversationTerminalBillingDecision(
        AiConversationTerminalBillingAction action,
        AiConversationUsage usage,
        String failureCode) {

    public AiConversationTerminalBillingDecision {
        Objects.requireNonNull(action);
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException(
                    "AI terminal billing failure code must not be blank.");
        }
        boolean settlement = action
                == AiConversationTerminalBillingAction.SETTLE_REPORTED_USAGE
                || action == AiConversationTerminalBillingAction
                        .SETTLE_ESTIMATED_CLIENT_CANCEL;
        if (settlement != (usage != null)) {
            throw new IllegalArgumentException(
                    "AI terminal billing usage does not match its action.");
        }
    }
}
