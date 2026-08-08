package com.example.temperate.functions.video.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示 FC 接收的版本内操作名与 JSON 负载，签名覆盖该对象的原始请求正文。
 */
public final class FcVideoRequest {

    private final String operation;
    private final JsonNode payload;
    private final String responseMode;

    @JsonCreator
    public FcVideoRequest(
            @JsonProperty("operation") String operation,
            @JsonProperty("payload") JsonNode payload,
            @JsonProperty("responseMode") String responseMode) {
        this.operation = operation;
        this.payload = payload;
        this.responseMode = responseMode;
    }

    public String operation() {
        return operation;
    }

    public JsonNode payload() {
        return payload;
    }

    /**
     * 返回客户端要求的响应形态；缺省时继续使用历史单个 JSON 响应，便于灰度发布和回滚。
     */
    public String responseMode() {
        return responseMode;
    }
}
