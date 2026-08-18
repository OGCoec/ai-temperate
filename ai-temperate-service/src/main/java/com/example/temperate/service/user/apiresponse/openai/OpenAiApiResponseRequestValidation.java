package com.example.temperate.service.user.apiresponse.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 该结果是来冻结 OpenAI Responses 原始负载的无状态传输与功能语义，模型授权和账单层只消费明确解析值。
 */
public record OpenAiApiResponseRequestValidation(
        ObjectNode payload,
        String model,
        boolean stream,
        Long requestedMaxOutputTokens,
        boolean functionTools,
        boolean structuredOutput) {

    public OpenAiApiResponseRequestValidation {
        payload = Objects.requireNonNull(payload).deepCopy();
        model = Objects.requireNonNull(model);
    }
}
