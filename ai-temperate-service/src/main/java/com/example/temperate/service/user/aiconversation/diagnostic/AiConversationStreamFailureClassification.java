package com.example.temperate.service.user.aiconversation.diagnostic;

import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import java.util.Objects;

/**
 * 承载一次流式失败的安全分类结果，仅包含异常类型、状态码、无消息堆栈指纹和已脱敏上游诊断。
 */
public record AiConversationStreamFailureClassification(
        AiConversationStreamFailureReason reason,
        int upstreamStatus,
        String exceptionType,
        String rootCauseType,
        String topApplicationFrame,
        String stackFingerprint,
        AiUpstreamErrorDiagnostic upstreamDiagnostic) {

    public AiConversationStreamFailureClassification {
        reason = Objects.requireNonNull(reason);
        exceptionType = Objects.requireNonNull(exceptionType);
        rootCauseType = Objects.requireNonNull(rootCauseType);
        topApplicationFrame = Objects.requireNonNull(topApplicationFrame);
        stackFingerprint = Objects.requireNonNull(stackFingerprint);
        upstreamDiagnostic = Objects.requireNonNull(upstreamDiagnostic);
    }

    /**
     * 保留既有分类调用约定；没有捕获上游正文的链路统一使用不可用诊断，避免伪造供应商信息。
     */
    public AiConversationStreamFailureClassification(
            AiConversationStreamFailureReason reason,
            int upstreamStatus,
            String exceptionType,
            String rootCauseType,
            String topApplicationFrame,
            String stackFingerprint) {
        this(
                reason,
                upstreamStatus,
                exceptionType,
                rootCauseType,
                topApplicationFrame,
                stackFingerprint,
                AiUpstreamErrorDiagnostic.unavailable());
    }
}
