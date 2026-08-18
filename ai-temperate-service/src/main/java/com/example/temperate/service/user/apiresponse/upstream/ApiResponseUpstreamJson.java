package com.example.temperate.service.user.apiresponse.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 该结果是来绑定 Responses 原始 JSON 与已筛选安全响应头，协议解析器只旁路读取终态和 Usage。
 */
public record ApiResponseUpstreamJson(
        JsonNode body,
        ApiInferenceUpstreamHeaders headers) {

    public ApiResponseUpstreamJson {
        Objects.requireNonNull(body);
        Objects.requireNonNull(headers);
    }
}
