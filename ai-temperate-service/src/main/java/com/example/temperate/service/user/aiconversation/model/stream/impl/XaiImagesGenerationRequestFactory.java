package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

/**
 * 按 xAI Images 公开协议白名单构造同步生成和编辑 JSON，请求始终固定单输出并要求 Base64 返回。
 */
final class XaiImagesGenerationRequestFactory {

    private static final int MAXIMUM_EDIT_REFERENCES = 3;

    private final ObjectMapper objectMapper;

    XaiImagesGenerationRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        Objects.requireNonNull(request);
        if (request.modelRequest().provider() != AiModelProvider.XAI) {
            throw new IllegalArgumentException(
                    "xAI image request requires the xAI provider.");
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
        root.put("prompt", prompt);
        root.put("n", 1);
        root.put("response_format", "b64_json");
        root.put("aspect_ratio", aspectRatio(image.aspect()));
        root.put("resolution", resolution(image.quality()));
        if (image.action() == AiConversationImageAction.EDIT) {
            addEditReferences(root, request.modelRequest().imageInputUrls());
        }
        return root;
    }

    private static void addEditReferences(
            ObjectNode root,
            List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()
                || imageUrls.size() > MAXIMUM_EDIT_REFERENCES) {
            throw new IllegalArgumentException(
                    "xAI image edit requires one to three references.");
        }
        for (String imageUrl : imageUrls) {
            if (imageUrl == null || !imageUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "xAI image edit references must use HTTPS.");
            }
        }
        if (imageUrls.size() == 1) {
            root.putObject("image")
                    .put("url", imageUrls.get(0))
                    .put("type", "image_url");
            return;
        }
        ArrayNode images = root.putArray("images");
        for (String imageUrl : imageUrls) {
            images.addObject()
                    .put("url", imageUrl)
                    .put("type", "image_url");
        }
    }

    private static String aspectRatio(AiConversationImageAspect aspect) {
        return switch (aspect) {
            case SQUARE -> "1:1";
            case LANDSCAPE -> "3:2";
            case PORTRAIT -> "2:3";
        };
    }

    private static String resolution(AiConversationImageQuality quality) {
        return switch (quality) {
            case LOW -> "1k";
            case HIGH -> "2k";
            case MEDIUM, ULTRA -> throw new IllegalArgumentException(
                    "xAI image generation does not support level 2.");
        };
    }
}
