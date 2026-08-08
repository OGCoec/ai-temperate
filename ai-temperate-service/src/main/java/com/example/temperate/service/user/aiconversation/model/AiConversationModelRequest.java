package com.example.temperate.service.user.aiconversation.model;

import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import java.util.Objects;
import java.util.List;

/**
 * 承载一次上游 SSE 调用的本地模型名、最大输出限制、推理强度、不可变 Prompt 快照和可选图片参数。
 */
public record AiConversationModelRequest(
        AiModelProvider provider,
        String modelName,
        long maxOutputTokens,
        AiConversationReasoningEffort reasoningEffort,
        AiConversationPromptSnapshot prompt,
        AiConversationImageGenerationOptions imageGeneration,
        short outputIndex,
        List<String> imageInputUrls,
        AiConversationVideoGenerationOptions videoGeneration,
        List<String> videoInputUrls) {

    public AiConversationModelRequest {
        provider = Objects.requireNonNull(provider);
        reasoningEffort = Objects.requireNonNull(reasoningEffort);
        imageInputUrls = imageInputUrls == null ? List.of() : List.copyOf(imageInputUrls);
        videoInputUrls = videoInputUrls == null ? List.of() : List.copyOf(videoInputUrls);
        if (imageGeneration != null && videoGeneration != null) {
            throw new IllegalArgumentException(
                    "Image and video generation cannot be combined.");
        }
        if (imageGeneration != null
                && (outputIndex < 0 || outputIndex >= imageGeneration.outputCount())) {
            throw new IllegalArgumentException("Image output index is out of range.");
        }
        if (videoGeneration != null
                && (!imageInputUrls.isEmpty() || outputIndex != 0)) {
            throw new IllegalArgumentException(
                    "Video generation cannot reuse image output slots.");
        }
    }

    public AiConversationModelRequest(
            AiModelProvider provider,
            String modelName,
            long maxOutputTokens,
            AiConversationReasoningEffort reasoningEffort,
            AiConversationPromptSnapshot prompt,
            AiConversationImageGenerationOptions imageGeneration,
            short outputIndex,
            List<String> imageInputUrls) {
        this(provider, modelName, maxOutputTokens, reasoningEffort, prompt,
                imageGeneration, outputIndex, imageInputUrls, null, List.of());
    }

    public AiConversationModelRequest(
            String modelName,
            long maxOutputTokens,
            AiConversationReasoningEffort reasoningEffort,
            AiConversationPromptSnapshot prompt,
            AiConversationImageGenerationOptions imageGeneration,
            short outputIndex,
            List<String> imageInputUrls) {
        this(AiModelProvider.OPENAI, modelName, maxOutputTokens,
                reasoningEffort, prompt, imageGeneration, outputIndex,
                imageInputUrls, null, List.of());
    }

    public AiConversationModelRequest(
            String modelName,
            long maxOutputTokens,
            AiConversationReasoningEffort reasoningEffort,
            AiConversationPromptSnapshot prompt,
            AiConversationImageGenerationOptions imageGeneration) {
        this(AiModelProvider.OPENAI, modelName, maxOutputTokens, reasoningEffort, prompt,
                imageGeneration, (short) 0, List.of(), null, List.of());
    }

    public AiConversationModelRequest(
            String modelName,
            long maxOutputTokens,
            AiConversationReasoningEffort reasoningEffort,
            AiConversationPromptSnapshot prompt) {
        this(AiModelProvider.OPENAI, modelName, maxOutputTokens, reasoningEffort, prompt,
                null, (short) 0, List.of(), null, List.of());
    }

    public AiConversationModelRequest(
            AiModelProvider provider,
            String modelName,
            long maxOutputTokens,
            AiConversationReasoningEffort reasoningEffort,
            AiConversationPromptSnapshot prompt) {
        this(provider, modelName, maxOutputTokens, reasoningEffort, prompt,
                null, (short) 0, List.of(), null, List.of());
    }
}
