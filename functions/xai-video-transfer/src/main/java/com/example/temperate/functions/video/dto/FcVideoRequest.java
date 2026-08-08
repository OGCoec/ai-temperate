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

    @JsonCreator
    public FcVideoRequest(
            @JsonProperty("operation") String operation,
            @JsonProperty("payload") JsonNode payload) {
        this.operation = operation;
        this.payload = payload;
    }

    public String operation() {
        return operation;
    }

    public JsonNode payload() {
        return payload;
    }
}
