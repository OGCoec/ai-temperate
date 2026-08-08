package com.example.temperate.service.user.aiconversation.generation.progress.impl;

import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgressPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 将已脱敏的媒体上传进度编码为临时输出事件，不写入 Redis 快照或生成终态。
 */
@Service
public final class AiConversationMediaUploadProgressPublisherImpl
        implements AiConversationMediaUploadProgressPublisher {

    private static final String EVENT_NAME = "media_upload_progress";

    private final AiConversationGenerationOutputStore outputStore;
    private final ObjectMapper objectMapper;

    public AiConversationMediaUploadProgressPublisherImpl(
            AiConversationGenerationOutputStore outputStore,
            ObjectMapper objectMapper) {
        this.outputStore = Objects.requireNonNull(outputStore);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void publish(String generationPublicId, AiConversationMediaUploadProgress progress) {
        if (generationPublicId == null || generationPublicId.isBlank()) {
            throw new IllegalArgumentException("Generation public id is required.");
        }
        try {
            outputStore.publishEvent(
                    generationPublicId,
                    EVENT_NAME,
                    objectMapper.writeValueAsString(Objects.requireNonNull(progress)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Media upload progress cannot be serialized.", exception);
        }
    }
}
