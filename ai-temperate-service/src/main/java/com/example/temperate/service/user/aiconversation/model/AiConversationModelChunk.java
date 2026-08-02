package com.example.temperate.service.user.aiconversation.model;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import java.util.List;

/**
 * 表示 Spring AI 从单次上游 SSE 解析出的文字增量、生成媒体以及可选最终 Usage 元数据。
 */
public record AiConversationModelChunk(
        String text,
        AiConversationUsage usage,
        String upstreamRequestId,
        String finishReason,
        List<AiConversationGeneratedMedia> generatedMedia,
        boolean generatedMediaTruncated) {

    public AiConversationModelChunk {
        text = text == null ? "" : text;
        generatedMedia = generatedMedia == null ? List.of() : List.copyOf(generatedMedia);
    }

    public AiConversationModelChunk(
            String text,
            AiConversationUsage usage,
            String upstreamRequestId,
            String finishReason,
            List<AiConversationGeneratedMedia> generatedMedia) {
        this(
                text,
                usage,
                upstreamRequestId,
                finishReason,
                generatedMedia,
                false);
    }

    public AiConversationModelChunk(
            String text,
            AiConversationUsage usage,
            String upstreamRequestId,
            String finishReason) {
        this(text, usage, upstreamRequestId, finishReason, List.of(), false);
    }
}
