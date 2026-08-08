package com.example.temperate.service.user.aiconversation.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 编解码 Payload 中的视频结果信封，只保存 OSS 引用与元数据，不保存临时 URL 或任何媒体字节。
 */
@Component
public final class AiConversationPersistedVideoResultCodec {

    private static final int SCHEMA_VERSION = 1;
    private final ObjectMapper objectMapper;

    public AiConversationPersistedVideoResultCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public String encode(AiConversationPersistedVideoResult result) {
        try {
            return objectMapper.writeValueAsString(
                    new Envelope(SCHEMA_VERSION, Objects.requireNonNull(result)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Persisted video result cannot be serialized.", exception);
        }
    }

    public AiConversationPersistedVideoResult decode(String json) {
        try {
            Envelope envelope = objectMapper.readValue(json, Envelope.class);
            if (envelope.schemaVersion() != SCHEMA_VERSION
                    || envelope.video() == null) {
                throw new IllegalArgumentException(
                        "Persisted video result schema is unsupported.");
            }
            return envelope.video();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Persisted video result JSON is invalid.", exception);
        }
    }

    /**
     * 固定视频结果的版本化 JSON 外层，防止未来字段演进误读旧数据。
     */
    private record Envelope(
            int schemaVersion,
            AiConversationPersistedVideoResult video) {
    }
}
