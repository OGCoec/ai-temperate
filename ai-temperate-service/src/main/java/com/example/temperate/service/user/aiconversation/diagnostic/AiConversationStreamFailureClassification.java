package com.example.temperate.service.user.aiconversation.diagnostic;

import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import java.util.Objects;

/**
 * 承载一次流式失败的安全分类结果，仅包含异常类型、状态码和无消息堆栈指纹等可记录元数据。
 */
public record AiConversationStreamFailureClassification(
        AiConversationStreamFailureReason reason,
        int upstreamStatus,
        String exceptionType,
        String rootCauseType,
        String topApplicationFrame,
        String stackFingerprint) {

    public AiConversationStreamFailureClassification {
        reason = Objects.requireNonNull(reason);
        exceptionType = Objects.requireNonNull(exceptionType);
        rootCauseType = Objects.requireNonNull(rootCauseType);
        topApplicationFrame = Objects.requireNonNull(topApplicationFrame);
        stackFingerprint = Objects.requireNonNull(stackFingerprint);
    }
}
