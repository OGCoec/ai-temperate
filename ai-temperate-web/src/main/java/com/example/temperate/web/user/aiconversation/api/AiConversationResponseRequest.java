package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 表示 AI 会话 SSE 接口的模型公共 ID、可选推理强度和多模态输入请求体。
 */
public record AiConversationResponseRequest(
        @NotNull
        @Pattern(regexp = PublicIdCodec.ENCODED_PATTERN)
        String modelPublicId,
        @Min(1)
        @Max(5)
        @Schema(
                description = "模型推理强度：1=Low、2=Medium、3=High、4=Extra High、5=Ultra；省略时使用 Medium",
                minimum = "1",
                maximum = "5",
                defaultValue = "2",
                example = "3")
        Short reasoningEffortLevel,
        @Schema(
                description = "联网搜索模式：OFF 保持普通对话，AUTO 允许模型自行搜索，REQUIRED 强制搜索；省略时为 OFF",
                defaultValue = "OFF",
                allowableValues = {"OFF", "AUTO", "REQUIRED"})
        AiConversationWebSearchMode webSearchMode,
        @NotNull
        @Valid
        AiConversationInputRequest input) {

    public AiConversationResponseRequest {
        webSearchMode = webSearchMode == null
                ? AiConversationWebSearchMode.OFF
                : webSearchMode;
    }
}
