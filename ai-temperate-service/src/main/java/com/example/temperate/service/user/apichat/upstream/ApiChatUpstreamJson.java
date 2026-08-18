package com.example.temperate.service.user.apichat.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 该结果是来绑定 Chat 上游非流式原始 JSON 与已筛选安全响应头，业务结算不会重建成功正文。
 */
public record ApiChatUpstreamJson(
        JsonNode body,
        ApiInferenceUpstreamHeaders headers) {

    public ApiChatUpstreamJson {
        Objects.requireNonNull(body);
        Objects.requireNonNull(headers);
    }
}
