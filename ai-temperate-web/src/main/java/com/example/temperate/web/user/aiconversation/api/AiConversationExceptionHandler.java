package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.web.user.aiconversation.controller.AiConversationGenerationController;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.user.aiconversation.controller.AiConversationResponseController;
import com.example.temperate.web.user.aiconversation.controller.AiConversationAttachmentController;
import com.example.temperate.web.user.aiconversation.controller.AiConversationQueryController;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 把 SSE 建立前的 AI 会话受控异常映射为稳定 JSON 错误，流开始后的错误由 error 事件承担。
 */
@RestControllerAdvice(assignableTypes = {
        AiConversationResponseController.class,
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
        return ResponseEntity.status(status(exception.code()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        message(exception),
                        clock.instant()));
    }

    /**
     * 吞掉客户端主动断开后产生的异步写失败；SSE 响应已经提交，不能再把 JSON 错误写进事件流。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(
            AsyncRequestNotUsableException exception) {
        LOGGER.debug(
                "event=ai_conversation_sse_client_disconnected cause={}",
                exception.getClass().getSimpleName());
    }

    /**
     * Servlet 写入器可能直接抛出 IOException，而不是先包装成
     * AsyncRequestNotUsableException；此时响应已经提交，继续返回 JSON 只会制造二次异常。
     */
    @ExceptionHandler(IOException.class)
    public void handleClientDisconnect(IOException exception) {
        LOGGER.debug(
                "event=ai_conversation_sse_client_disconnected cause={}",
                exception.getClass().getSimpleName());
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
            case AI_CONTEXT_TOO_LARGE, AI_MODEL_LIMITS_MISSING,
                    AI_QUOTA_RULE_MISSING,
                    AI_MODEL_REASONING_LEVEL_UNSUPPORTED,
                    AI_IMAGE_RESOLUTION_UNSUPPORTED,
                    AI_PROVIDER_TOOL_UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AI_UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AI_UPSTREAM_UNAVAILABLE,
                    AI_CONTEXT_CACHE_UNAVAILABLE,
                    AI_RUNTIME_LINKAGE_FAILED ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case AI_CONCURRENCY_LIMIT_REACHED -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_CONTEXT_COMPACTION_FAILED,
                    AI_UPSTREAM_STREAM_FAILED,
                    AI_USAGE_UNAVAILABLE,
                    AI_SETTLEMENT_RECONCILE_REQUIRED ->
                    HttpStatus.BAD_GATEWAY;
        };
    }
}
