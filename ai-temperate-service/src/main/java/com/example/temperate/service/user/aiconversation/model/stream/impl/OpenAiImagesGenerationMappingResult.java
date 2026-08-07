package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import java.util.List;
import java.util.Objects;

/**
 * 汇总图片 SSE 的安全协议元数据与业务事件；只保存编码长度，不保留原始 Base64 正文。
 */
record OpenAiImagesGenerationMappingResult(
        String upstreamEventName,
        String upstreamJsonType,
        Integer partialImageIndex,
        String imagePayloadField,
        int eventCharacters,
        int encodedImageCharacters,
        OpenAiImagesGenerationMappingOutcome outcome,
        List<AiConversationModelEvent> events) {

    OpenAiImagesGenerationMappingResult {
        upstreamEventName = normalized(upstreamEventName);
        upstreamJsonType = normalized(upstreamJsonType);
        imagePayloadField = normalized(imagePayloadField);
        if (eventCharacters < 0 || encodedImageCharacters < 0) {
            throw new IllegalArgumentException(
                    "Image diagnostic character counts must not be negative");
        }
        outcome = Objects.requireNonNull(outcome);
        events = List.copyOf(Objects.requireNonNull(events));
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }
}
