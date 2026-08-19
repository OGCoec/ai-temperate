package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.web.user.aiconversation.controller.AiConversationGenerationController;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.aiinference.ApiInferenceClientDisconnectClassifier;
import com.example.temperate.web.user.aiconversation.controller.AiConversationResponseController;
import com.example.temperate.web.user.aiconversation.controller.AiConversationContextController;
import com.example.temperate.web.user.aiconversation.controller.AiConversationAttachmentController;
import com.example.temperate.web.user.aiconversation.controller.AiConversationQueryController;
import com.example.temperate.web.user.aiconversation.diagnostic.AiConversationRequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 该处理器是来把 AI 会话建流前的受控异常映射为稳定 JSON，并在 SSE 已提交后阻止客户端断开触发二次响应写入。
 */
@RestControllerAdvice(assignableTypes = {
        AiConversationResponseController.class,
        AiConversationContextController.class,
        AiConversationGenerationController.class,
        AiConversationAttachmentController.class,
        AiConversationQueryController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AiConversationExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiConversationExceptionHandler.class);

    private final Clock clock;

    public AiConversationExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @ExceptionHandler(AiConversationException.class)
    public ResponseEntity<ApiErrorResponse> handle(
            AiConversationException exception) {
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(status(exception.code()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.APPLICATION_JSON);
        if (exception.code()
                == AiConversationErrorCode.AI_CONTEXT_COMPACTION_TIMEOUT) {
            builder.header("Retry-After", "60");
        }
        return builder.body(new ApiErrorResponse(
                        exception.code().name(),
                        message(exception),
                        clock.instant()));
    }

    /**
     * 吞掉客户端主动断开后产生的异步写失败；SSE 响应已经提交，不能再把 JSON 错误写进事件流。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<ApiErrorResponse> handleClientDisconnect(
            AsyncRequestNotUsableException exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleIoFailure(exception, request, response);
    }

    /**
     * Servlet 写入器可能直接抛出 IOException，而不是先包装成
     * AsyncRequestNotUsableException；此时响应已经提交，继续返回 JSON 只会制造二次异常。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> handleClientDisconnect(
            IOException exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        return handleIoFailure(exception, request, response);
    }

    private ResponseEntity<ApiErrorResponse> handleIoFailure(
            Throwable exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        ApiInferenceClientDisconnectClassifier.Result result =
                ApiInferenceClientDisconnectClassifier.classify(exception, response);
        if (result == ApiInferenceClientDisconnectClassifier.Result
                .COMMITTED_SSE_CLIENT_DISCONNECT) {
            if (ApiInferenceClientDisconnectClassifier.claimDiagnostic(request)) {
                safeDebug(
                        "event=ai_conversation_sse_client_disconnected traceId={} exceptionType={} outcome=client_disconnected",
                        traceId(request),
                        exception.getClass().getName());
            }
            return null;
        }
        if (result == ApiInferenceClientDisconnectClassifier.Result
                .COMMITTED_RESPONSE_IO_FAILURE) {
            if (ApiInferenceClientDisconnectClassifier.claimDiagnostic(request)) {
                safeWarn(
                        "event=ai_conversation_committed_response_io_failure traceId={} exceptionType={} outcome=response_already_committed",
                        traceId(request),
                        exception.getClass().getName());
            }
            return null;
        }
        // 建流前仍有能力返回 JSON；此处只暴露稳定业务码，不回传 Servlet 或操作系统异常消息。
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(
                        AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE.name(),
                        "模型服务暂时不可用，请稍后重试。",
                        clock.instant()));
    }

    private static String traceId(HttpServletRequest request) {
        Object attribute = request == null ? null : request.getAttribute(
                AiConversationRequestTraceFilter.TRACE_ATTRIBUTE);
        String value = attribute instanceof String trace
                ? trace : MDC.get(AiConversationRequestTraceFilter.TRACE_MDC_KEY);
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "absent";
    }

    private static void safeDebug(String template, Object... arguments) {
        try {
            LOGGER.debug(template, arguments);
        } catch (RuntimeException ignored) {
            // 日志后端失败不能重新触发已提交会话 SSE 的错误处理。
        }
    }

    private static void safeWarn(String template, Object... arguments) {
        try {
            LOGGER.warn(template, arguments);
        } catch (RuntimeException ignored) {
            // 已提交非 SSE 响应无法改写，诊断失败时只能保留原终止行为。
        }
    }

    private static String message(AiConversationException exception) {
        if (exception.code() == AiConversationErrorCode.AI_QUOTA_INSUFFICIENT) {
            return "额度不足，请充值。";
        }
        return exception.getMessage();
    }

    private static HttpStatus status(AiConversationErrorCode code) {
        return switch (code) {
            case AI_REQUEST_INVALID, AI_ATTACHMENT_INVALID -> HttpStatus.BAD_REQUEST;
            case AI_ATTACHMENT_CAPABILITY_UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AI_ATTACHMENT_STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_CONVERSATION_NOT_FOUND, AI_MODEL_NOT_AVAILABLE ->
                    HttpStatus.NOT_FOUND;
            case AI_CONVERSATION_BUSY, AI_IDEMPOTENCY_CONFLICT ->
                    HttpStatus.CONFLICT;
            case AI_QUOTA_INSUFFICIENT -> HttpStatus.PAYMENT_REQUIRED;
            case AI_MODEL_LIMITS_MISSING,
                    AI_QUOTA_RULE_MISSING,
                    AI_MODEL_REASONING_LEVEL_UNSUPPORTED,
                    AI_IMAGE_RESOLUTION_UNSUPPORTED,
                    AI_PROVIDER_TOOL_UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AI_UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AI_UPSTREAM_UNAVAILABLE,
                    AI_CONTEXT_CACHE_UNAVAILABLE,
                    AI_RUNTIME_LINKAGE_FAILED ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case AI_VIDEO_OSS_TRANSFER_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_CONCURRENCY_LIMIT_REACHED -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_CONTEXT_TOO_LARGE -> HttpStatus.CONFLICT;
            case AI_CONTEXT_COMPACTION_FAILED,
                    AI_CONTEXT_COMPACTION_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_UPSTREAM_STREAM_FAILED,
                    AI_VIDEO_XAI_REJECTED,
                    AI_VIDEO_XAI_FAILED,
                    AI_VIDEO_XAI_EXPIRED,
                    AI_VIDEO_XAI_RESULT_UNCERTAIN,
                    AI_USAGE_UNAVAILABLE,
                    AI_SETTLEMENT_RECONCILE_REQUIRED ->
                    HttpStatus.BAD_GATEWAY;
        };
    }
}
