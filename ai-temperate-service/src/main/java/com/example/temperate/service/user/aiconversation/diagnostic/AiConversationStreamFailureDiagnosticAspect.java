package com.example.temperate.service.user.aiconversation.diagnostic;

import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import java.util.Arrays;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 在安全分类完成后输出一次结构化 AI 流终止日志，禁止展开异常消息、请求正文和模型输出。
 */
@Aspect
@Component
public final class AiConversationStreamFailureDiagnosticAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AiConversationStreamFailureDiagnosticAspect.class);
    private static final String EVENT = "ai_conversation_stream_failed";
    private static final String LOG_TEMPLATE =
            "event=ai_conversation_stream_failed traceId={} usagePublicId={} conversationPublicId={} "
                    + "modelPublicId={} errorCode={} reasonCode={} retryable={} "
                    + "exceptionType={} rootCauseType={} upstreamStatus={} "
                    + "emittedDeltaCount={} emittedTextChars={} elapsedMs={} "
                    + "billingState={} refundOutcome={} topApplicationFrame={} "
                    + "stackFingerprint={} upstreamErrorCode={} upstreamErrorType={} "
                    + "upstreamErrorParam={} upstreamErrorMessage=\"{}\" "
                    + "upstreamRequestId={} upstreamContentType={} upstreamBodySha256={} "
                    + "upstreamCapturedBytes={} upstreamBodyTruncated={}";

    /**
     * 切面只观察诊断 Service 的同步分类结果；真正的异步异常已经由调用方作为参数显式传入。
     */
    @Around("@annotation("
            + "com.example.temperate.service.user.aiconversation.diagnostic."
            + "AiConversationStreamFailureDiagnostic)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable diagnosticFailure) {
            LOGGER.error(
                    "event={} outcome=diagnostic_failed exceptionType={}",
                    EVENT,
                    diagnosticFailure.getClass().getName());
            throw diagnosticFailure;
        }
        AiConversationStreamFailureContext context = Arrays.stream(
                        joinPoint.getArgs())
                .filter(AiConversationStreamFailureContext.class::isInstance)
                .map(AiConversationStreamFailureContext.class::cast)
                .findFirst()
                .orElse(null);
        if (context == null
                || !(result instanceof AiConversationStreamFailureClassification
                        classification)) {
            LOGGER.error(
                    "event={} outcome=diagnostic_contract_invalid",
                    EVENT);
            return result;
        }
        log(context, classification);
        return result;
    }

    private static void log(
            AiConversationStreamFailureContext context,
            AiConversationStreamFailureClassification classification) {
        AiUpstreamErrorDiagnostic upstream =
                classification.upstreamDiagnostic();
        Object[] arguments = {
            context.traceId(),
            context.usagePublicId(),
            context.conversationPublicId(),
            context.modelPublicId(),
            context.errorCode(),
            classification.reason().name(),
            context.retryable(),
            classification.exceptionType(),
            classification.rootCauseType(),
            classification.upstreamStatus(),
            context.emittedDeltaCount(),
            context.emittedTextChars(),
            context.elapsedMs(),
            context.billingState(),
            context.refundOutcome(),
            classification.topApplicationFrame(),
            classification.stackFingerprint(),
            upstream.providerCode(),
            upstream.providerType(),
            upstream.providerParam(),
            upstream.sanitizedMessage(),
            upstream.requestId(),
            upstream.contentType(),
            upstream.bodySha256(),
            upstream.capturedBytes(),
            upstream.truncated()
        };
        if (classification.reason()
                        == AiConversationStreamFailureReason.UNKNOWN_STREAM_FAILURE
                || classification.reason()
                        == AiConversationStreamFailureReason.STREAM_BACKPRESSURE_OVERFLOW
                || "RECONCILE_REQUIRED".equals(context.billingState())) {
            LOGGER.error(LOG_TEMPLATE, arguments);
        } else {
            LOGGER.warn(LOG_TEMPLATE, arguments);
        }
    }
}
