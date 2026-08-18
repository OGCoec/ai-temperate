package com.example.temperate.service.user.apiresponse.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 该结果是来把非流式 Responses 原始 JSON 与已验证终态、Usage 和安全结束原因绑定，结算成功后才能返回原文。
 */
public record ApiResponseJsonResult(
        JsonNode response,
        Status status,
        ApiInferenceUsage usage,
        String finishReason) {

    /** 该枚举是来限定非流式响应可被本服务接受的三个权威状态。 */
    public enum Status {
        COMPLETED,
        INCOMPLETE,
        FAILED
    }
}
