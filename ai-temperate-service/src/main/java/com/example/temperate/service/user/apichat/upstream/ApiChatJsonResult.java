package com.example.temperate.service.user.apichat.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 该结果是来绑定 Chat 非流式原始 JSON 与旁路验证的 Usage 和结束原因，HTTP 200 必须等待这些事实完成结算。
 */
public record ApiChatJsonResult(
        JsonNode response,
        ApiInferenceUsage usage,
        String finishReason) {

    public ApiChatJsonResult {
        Objects.requireNonNull(response);
        Objects.requireNonNull(usage);
        Objects.requireNonNull(finishReason);
    }
}
