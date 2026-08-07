package com.example.temperate.service.user.aiconversation.model.stream;

import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import java.util.Objects;

/**
 * 组合冻结的模型请求与联网搜索意图，供协议策略在不读取 Web DTO 的情况下构造上游请求。
 */
public record AiConversationStreamingRequest(
        AiConversationModelRequest modelRequest,
        AiConversationWebSearchMode webSearchMode,
        AiConversationStreamingDiagnosticContext diagnosticContext) {

    public AiConversationStreamingRequest(
            AiConversationModelRequest modelRequest,
            AiConversationWebSearchMode webSearchMode) {
        this(modelRequest, webSearchMode, null);
    }

    public AiConversationStreamingRequest {
        modelRequest = Objects.requireNonNull(modelRequest);
        webSearchMode = Objects.requireNonNull(webSearchMode);
    }
}
