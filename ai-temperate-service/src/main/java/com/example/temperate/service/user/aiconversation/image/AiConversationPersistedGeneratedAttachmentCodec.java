package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 编解码图片 Generation 的最终附件 URL 信封，数据库证据只含元数据且不允许出现模型图片字节。
 */
@Component
public final class AiConversationPersistedGeneratedAttachmentCodec {

    private static final int LEGACY_SCHEMA_VERSION = 1;
    private static final int SCHEMA_VERSION = 2;
    private final ObjectMapper objectMapper;

    public AiConversationPersistedGeneratedAttachmentCodec(
            ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public String encode(List<AiConversationAttachment> attachments) {
        try {
            List<AiConversationAttachment> safe = validated(attachments);
            return objectMapper.writeValueAsString(new Envelope(
                    SCHEMA_VERSION,
                    safe));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Generated image attachment metadata is invalid", exception);
        }
    }

    public List<AiConversationAttachment> decode(String json) {
        try {
            Envelope envelope = objectMapper.readValue(json, Envelope.class);
            if (envelope.schemaVersion() != LEGACY_SCHEMA_VERSION
                    && envelope.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported generated image attachment schema version");
            }
            return validated(envelope.attachments());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Generated image attachment metadata is invalid", exception);
        }
    }

    private static List<AiConversationAttachment> validated(
            List<AiConversationAttachment> attachments) {
        List<AiConversationAttachment> safe = List.copyOf(
                attachments == null ? List.of() : attachments);
        if (safe.size() > 10) {
            throw new IllegalArgumentException(
                    "Image generation persists at most ten images");
        }
        for (AiConversationAttachment attachment : safe) {
            if (attachment == null
                    || attachment.state() != AiConversationAttachmentState.AVAILABLE
                    || attachment.category() != AiConversationAttachmentCategory.IMAGE
                    || !attachment.contentType().startsWith("image/")
                    || attachment.url() == null
                    || !attachment.url().startsWith("https://")) {
                throw new IllegalArgumentException(
                        "Each generated image attachment must contain a public HTTPS image URL");
            }
        }
        return safe;
    }

    private record Envelope(
            int schemaVersion,
            List<AiConversationAttachment> attachments) {
    }
}
