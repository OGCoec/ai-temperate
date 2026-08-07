package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

/**
 * 构造 Google Interactions 单图生成或最多三张参考图编辑请求，并省略文本 thinking_level。
 */
final class GeminiInteractionsImageRequestFactory {

    private static final int MAXIMUM_EDIT_REFERENCES = 3;

    private final ObjectMapper objectMapper;

    GeminiInteractionsImageRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        Objects.requireNonNull(request);
        if (request.modelRequest().provider() != AiModelProvider.GOOGLE) {
            throw new IllegalArgumentException(
                    "Google image request requires the Google provider.");
        }
        if (request.webSearchMode() != AiConversationWebSearchMode.OFF) {
            throw new IllegalArgumentException(
                    "Google image request cannot enable web search.");
        }
        AiConversationImageGenerationOptions image = Objects.requireNonNull(
                request.modelRequest().imageGeneration(),
                "Image generation options are required.");
        String prompt = request.modelRequest().prompt().currentInput().text();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Image prompt must not be blank.");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelRequest().modelName());
        root.put("stream", true);
        root.put("store", false);
        ArrayNode content = root.putArray("input")
                .addObject()
                .put("type", "user_input")
                .putArray("content");
        content.addObject().put("type", "text").put("text", prompt);
        if (image.action() == AiConversationImageAction.EDIT) {
            addEditReferences(content, request.modelRequest().imageInputUrls());
        }
        root.putObject("generation_config")
                .put("max_output_tokens", request.modelRequest().maxOutputTokens());
        root.putObject("response_format")
                .put("type", "image")
                .put("delivery", "inline")
                .put("mime_type", "image/jpeg")
                .put("aspect_ratio", aspectRatio(image.aspect()))
                .put("image_size", imageSize(image.quality()));
        return root;
    }

    private static void addEditReferences(
            ArrayNode content,
            List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()
                || imageUrls.size() > MAXIMUM_EDIT_REFERENCES) {
            throw new IllegalArgumentException(
                    "Google image edit requires one to three references.");
        }
        for (String imageUrl : imageUrls) {
            if (imageUrl == null || !imageUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "Google image edit references must use HTTPS.");
            }
            content.addObject()
                    .put("type", "image")
                    .put("uri", imageUrl);
        }
    }

    private static String aspectRatio(AiConversationImageAspect aspect) {
        return switch (aspect) {
            case SQUARE -> "1:1";
            case LANDSCAPE -> "3:2";
            case PORTRAIT -> "2:3";
        };
    }

    private static String imageSize(AiConversationImageQuality quality) {
        return switch (quality) {
            case LOW -> "512";
            case MEDIUM -> "1K";
            case HIGH -> "2K";
            case ULTRA -> "4K";
        };
    }
}
