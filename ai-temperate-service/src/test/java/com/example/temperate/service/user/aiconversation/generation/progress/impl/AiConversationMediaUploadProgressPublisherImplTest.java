package com.example.temperate.service.user.aiconversation.generation.progress.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaType;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证上传进度被序列化为独立临时 SSE 事件，避免污染生成文本和终态快照。
 */
final class AiConversationMediaUploadProgressPublisherImplTest {

    @Test
    void publishesSanitizedTransientMediaUploadProgressEvent() throws Exception {
        AiConversationGenerationOutputStore outputStore = mock(
                AiConversationGenerationOutputStore.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AiConversationMediaUploadProgressPublisherImpl publisher =
                new AiConversationMediaUploadProgressPublisherImpl(outputStore, objectMapper);

        publisher.publish("generation-safe", new AiConversationMediaUploadProgress(
                AiConversationMediaType.VIDEO,
                0,
                1,
                1,
                AiConversationMediaUploadState.UPLOADING,
                5_000_000L,
                10_000_000L,
                50,
                7L,
                null));

        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(outputStore).publishEvent(
                org.mockito.ArgumentMatchers.eq("generation-safe"),
                org.mockito.ArgumentMatchers.eq("media_upload_progress"),
                dataCaptor.capture());
        JsonNode data = objectMapper.readTree(dataCaptor.getValue());
        assertThat(data.path("mediaType").asText()).isEqualTo("VIDEO");
        assertThat(data.path("percent").asInt()).isEqualTo(50);
        assertThat(data.toString()).doesNotContain("sourceUrl").doesNotContain("secret");
    }
}
