package com.example.temperate.service.user.aiconversation.model.stream;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import java.util.Objects;

/**
 * 携带单次模型流的安全诊断关联信息，使 Reactor 切换线程后仍能关联 Generation，且不保存请求正文。
 *
 * @param timingContext 流式时序诊断上下文
 * @param generationPublicId Generation 公共 ID
 */
public record AiConversationStreamingDiagnosticContext(
        AiConversationStreamTimingContext timingContext,
        String generationPublicId) {

    public AiConversationStreamingDiagnosticContext {
        timingContext = Objects.requireNonNull(timingContext);
        if (generationPublicId == null || generationPublicId.isBlank()) {
            throw new IllegalArgumentException(
                    "generationPublicId must not be blank");
        }
    }
}
