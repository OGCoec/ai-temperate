package com.example.temperate.service.user.apichat.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 该结果是来冻结 OpenAI Chat 原始负载的传输模式、Token 请求和功能语义，后续模型授权与计费不得重新猜测字段。
 */
public record OpenAiApiChatRequestValidation(
        ObjectNode payload,
        String model,
        boolean stream,
        Long requestedMaxOutputTokens,
        boolean includeUsage,
        boolean functionTools,
        boolean structuredOutput) {

    public OpenAiApiChatRequestValidation {
        payload = Objects.requireNonNull(payload).deepCopy();
        model = Objects.requireNonNull(model);
    }
}
