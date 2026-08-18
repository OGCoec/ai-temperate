package com.example.temperate.service.user.openaicompatibility;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 该结果是来冻结规范化负载、流模式、账单上限、Usage 可见性和丢弃计数，后续阶段不得重新解释客户端原始字段。
 */
public record LooseOpenAiRequestNormalization(
        ObjectNode normalizedPayload,
        boolean stream,
        long effectiveMaxOutputTokens,
        boolean includeUsage,
        OpenAiRequestPayloadMode payloadMode,
        int droppedFieldCount) {

    public LooseOpenAiRequestNormalization {
        normalizedPayload = Objects.requireNonNull(normalizedPayload).deepCopy();
        payloadMode = Objects.requireNonNull(payloadMode);
        if (effectiveMaxOutputTokens <= 0L) {
            throw new IllegalArgumentException("Effective output token limit must be positive");
        }
        if (droppedFieldCount < 0) {
            throw new IllegalArgumentException("Dropped field count cannot be negative");
        }
    }

    @Override
    public ObjectNode normalizedPayload() {
        return normalizedPayload.deepCopy();
    }
}
