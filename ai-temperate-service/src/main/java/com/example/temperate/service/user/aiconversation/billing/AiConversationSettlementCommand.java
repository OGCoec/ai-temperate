package com.example.temperate.service.user.aiconversation.billing;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import java.util.List;

/**
 * 承载一次可靠上游 usage、完整回答和持久化消息内容的最终结算输入。
 */
public record AiConversationSettlementCommand(
        byte[] usageId,
        Long messageId,
        AiConversationContent user,
        AiConversationContent assistant,
        List<String> userSearchTokens,
        long promptTokens,
        long cachedPromptTokens,
        long completionTokens,
        long reasoningTokens,
        String upstreamRequestId,
        String finishReason,
        AiConversationLifecycleTraceContext traceContext) {

    public AiConversationSettlementCommand {
        usageId = usageId.clone();
        userSearchTokens = userSearchTokens == null
                ? List.of()
                : List.copyOf(userSearchTokens);
        traceContext = traceContext == null
                ? AiConversationLifecycleTraceContext.unavailable()
                : traceContext;
    }

    public AiConversationSettlementCommand(
            byte[] usageId,
            Long messageId,
            AiConversationContent user,
            AiConversationContent assistant,
            List<String> userSearchTokens,
            long promptTokens,
            long cachedPromptTokens,
            long completionTokens,
            long reasoningTokens,
            String upstreamRequestId,
            String finishReason) {
        this(
                usageId,
                messageId,
                user,
                assistant,
                userSearchTokens,
                promptTokens,
                cachedPromptTokens,
                completionTokens,
                reasoningTokens,
                upstreamRequestId,
                finishReason,
                AiConversationLifecycleTraceContext.unavailable());
    }
}
