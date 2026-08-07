package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImageFormat;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageMeteringEvidence;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageMeteringStatus;
import com.example.temperate.service.user.aiconversation.model.AiConversationProviderCostUsage;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 将 xAI 同步图片响应映射为最终图片和强类型成本证据，并在成本缺失时保留可交付图片。
 */
final class XaiImagesGenerationEventMapper {

    private final int maximumDecodedBytes;

    XaiImagesGenerationEventMapper(int maximumDecodedBytes) {
        if (maximumDecodedBytes <= 0) {
            throw new IllegalArgumentException(
                    "maximumDecodedBytes must be positive.");
        }
        this.maximumDecodedBytes = maximumDecodedBytes;
    }

    List<AiConversationModelEvent> map(
            JsonNode root,
            AiConversationImageGenerationOptions options,
            short outputIndex) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(options);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != 1) {
            throw new IllegalStateException(
                    "xAI image response must contain exactly one image.");
        }
        JsonNode item = data.get(0);
        byte[] bytes = decode(text(item, "b64_json"));
        AiConversationGeneratedImageFormat format =
                AiConversationGeneratedImageFormat.detect(bytes);
        String requestId = firstText(root, "request_id", "requestId", "id");
        String imageId = firstText(item, "id", "image_id");
        if (imageId == null || imageId.isBlank()) {
            imageId = "xai-image-" + outputIndex;
        }

        List<AiConversationModelEvent> events = new ArrayList<>();
        events.add(new AiConversationModelEvent.Image(
                new AiConversationGeneratedImage(
                        imageId,
                        AiConversationGeneratedImagePhase.FINAL,
                        outputIndex,
                        null,
                        format.contentType(),
                        options.aspect().width(),
                        options.aspect().height(),
                        bytes)));
        JsonNode cost = root.path("usage").path("cost_in_usd_ticks");
        if (cost.isIntegralNumber() && cost.canConvertToLong()
                && cost.longValue() >= 0L) {
            long ticks = cost.longValue();
            events.add(new AiConversationModelEvent.ImageUsage(
                    outputIndex,
                    new AiConversationProviderCostUsage(ticks),
                    requestId,
                    "STOP"));
            return List.copyOf(events);
        }
        AiConversationImageMeteringStatus status =
                cost.isMissingNode() || cost.isNull()
                        ? AiConversationImageMeteringStatus.MISSING_COST
                        : AiConversationImageMeteringStatus.INVALID_COST;
        events.add(new AiConversationModelEvent.ImageCostEvidence(
                new AiConversationImageMeteringEvidence(
                        outputIndex, status, requestId, null)));
        return List.copyOf(events);
    }

    private byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "xAI image response does not contain Base64 data.");
        }
        long estimated = (long) value.length() * 3L / 4L;
        if (estimated > maximumDecodedBytes + 2L) {
            throw new IllegalStateException(
                    "xAI image response exceeds the decoded byte limit.");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 0 || bytes.length > maximumDecodedBytes) {
                throw new IllegalStateException(
                        "xAI image response exceeds the decoded byte limit.");
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "xAI image response contains invalid Base64.", exception);
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
