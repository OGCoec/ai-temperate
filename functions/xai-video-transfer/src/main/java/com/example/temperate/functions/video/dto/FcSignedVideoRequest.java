package com.example.temperate.functions.video.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示主业务服务发送的正文级 HMAC 信封，使 FC Handler 不依赖 HTTP 触发器请求头透传语义。
 */
public final class FcSignedVideoRequest {

    private final String timestamp;
    private final String nonce;
    private final String signature;
    private final JsonNode request;

    @JsonCreator
    public FcSignedVideoRequest(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("nonce") String nonce,
            @JsonProperty("signature") String signature,
            @JsonProperty("request") JsonNode request) {
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.signature = signature;
        this.request = request;
    }

    public String timestamp() {
        return timestamp;
    }

    public String nonce() {
        return nonce;
    }

    public String signature() {
        return signature;
    }

    public JsonNode request() {
        return request;
    }
}
