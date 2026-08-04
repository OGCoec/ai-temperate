package com.example.temperate.service.user.aiconversation.image;

import java.util.Objects;

/**
 * 承载 Web 边界校验后的文字生成图片画幅意图，不允许客户端注入原始上游质量或尺寸。
 */
public record AiConversationImageGenerationRequest(
        AiConversationImageAspect aspect) {

    public AiConversationImageGenerationRequest {
        aspect = Objects.requireNonNull(aspect);
    }
}
