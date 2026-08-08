package com.example.temperate.service.user.aiconversation.video;

import java.util.List;
import java.util.Objects;

/**
 * 承载 Web 边界传入的视频模式、时长、画质、比例和附件公开标识，不包含媒体 URL 或二进制内容。
 */
public record AiConversationVideoGenerationRequest(
        AiConversationVideoMode mode,
        Integer durationSeconds,
        AiConversationVideoResolution resolution,
        AiConversationVideoAspectRatio aspectRatio,
        List<String> inputAttachmentPublicIds) {

    public AiConversationVideoGenerationRequest {
        mode = Objects.requireNonNull(mode);
        inputAttachmentPublicIds = inputAttachmentPublicIds == null
                ? List.of()
                : List.copyOf(inputAttachmentPublicIds);
    }
}
