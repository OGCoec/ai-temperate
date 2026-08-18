package com.example.temperate.service.user.aiinference.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 该异常是来携带已证明安全的 OpenAI 上游错误状态、原始 error envelope 和允许公开的响应头，禁止承载内部原始正文。
 */
public final class ApiInferenceUpstreamException extends RuntimeException {

    private final int status;
    private final ObjectNode envelope;
    private final ApiInferenceUpstreamHeaders headers;

    public ApiInferenceUpstreamException(
            int status,
            ObjectNode envelope,
            ApiInferenceUpstreamHeaders headers) {
        super("The OpenAI upstream returned a safe error envelope.");
        this.status = status;
        this.envelope = Objects.requireNonNull(envelope).deepCopy();
        this.headers = Objects.requireNonNull(headers);
    }

    public int status() {
        return status;
    }

    public ObjectNode envelope() {
        return envelope.deepCopy();
    }

    public ApiInferenceUpstreamHeaders headers() {
        return headers;
    }
}
