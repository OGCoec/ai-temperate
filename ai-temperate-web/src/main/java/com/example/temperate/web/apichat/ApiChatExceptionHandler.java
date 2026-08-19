package com.example.temperate.web.apichat;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamException;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticParameter;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticParameter;
import com.example.temperate.web.apikey.ApiInferenceBodyLimitFilter.PayloadTooLargeException;
import com.example.temperate.web.aiinference.ApiInferenceClientDisconnectClassifier;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 该异常处理器是来把公开 Chat 与 Responses 在提交前的失败转换为 OpenAI JSON，并在流已提交后安全终止客户端断开且不暴露异常细节。
 */
@Order(-100)
@RestControllerAdvice(assignableTypes = {
        ApiChatCompletionController.class,
        com.example.temperate.web.apiresponse.ApiResponsesController.class})
public final class ApiChatExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApiChatExceptionHandler.class);

    /**
     * 已提交响应绝不能再生成 JSON；未提交的写入异常仍映射为稳定的 OpenAI 上游不可用错误。
     */
    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class})
    public Object ioFailure(
            Throwable exception,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        ApiInferenceClientDisconnectClassifier.Result result =
                ApiInferenceClientDisconnectClassifier.classify(
                        exception, servletResponse);
        if (result == ApiInferenceClientDisconnectClassifier.Result
                .COMMITTED_SSE_CLIENT_DISCONNECT) {
            if (ApiInferenceClientDisconnectClassifier.claimDiagnostic(request)) {
                safeDebug(
                        "event=api_inference_client_disconnected protocol={} traceId={} exceptionType={} outcome=client_disconnected",
                        protocol(request),
                        safeTraceId(MDC.get("apiChatTraceId")),
                        safeType(exception.getClass()));
            }
            return null;
        }
        if (result == ApiInferenceClientDisconnectClassifier.Result
                .COMMITTED_RESPONSE_IO_FAILURE) {
            if (ApiInferenceClientDisconnectClassifier.claimDiagnostic(request)) {
                safeLog(
                        "event=api_inference_committed_response_io_failure protocol={} traceId={} exceptionType={} outcome=response_already_committed",
                        protocol(request),
                        safeTraceId(MDC.get("apiChatTraceId")),
                        safeType(exception.getClass()));
            }
            return null;
        }
        return mappedResponse(
                "IO_FAILURE",
                exception.getClass(),
                ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                "The upstream service is temporarily unavailable.",
                null,
                ApiChatException.ValidationReason.UNSPECIFIED,
                request,
                servletResponse);
    }

    @ExceptionHandler(
            value = ApiInferenceUpstreamException.class,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> upstreamOpenAiError(
            ApiInferenceUpstreamException exception) {
        HttpHeaders headers = exception.headers().toHttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore().cachePrivate().noTransform());
        headers.set("CDN-Cache-Control", "no-store");
        return new ResponseEntity<>(
                exception.envelope(),
                headers,
                HttpStatusCode.valueOf(exception.status()));
    }

    @ExceptionHandler(value = ApiChatException.class, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiChatErrorResponse> handle(
            ApiChatException exception,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        return mappedResponse(
                "API_CHAT_EXCEPTION",
                exception.getClass(),
                exception.code(),
                exception.getMessage(),
                exception.parameter(),
                exception.validationReason(),
                request,
                servletResponse);
    }

    @ExceptionHandler(value = HttpMessageNotReadableException.class,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiChatErrorResponse> unreadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ApiChatException apiChatException) {
                return mappedResponse(
                        "UNREADABLE_WRAPPED_API_CHAT",
                        apiChatException.getClass(),
                        apiChatException.code(),
                        apiChatException.getMessage(),
                        apiChatException.parameter(),
                        apiChatException.validationReason(),
                        request,
                        servletResponse);
            }
            if (current instanceof PayloadTooLargeException) {
                return mappedResponse(
                        "PAYLOAD_TOO_LARGE",
                        current.getClass(),
                        ApiChatErrorCode.INVALID_REQUEST,
                        "The request body is too large.",
                        null,
                        ApiChatException.ValidationReason.UNSPECIFIED,
                        request,
                        servletResponse);
            }
            current = current.getCause();
        }
        return mappedResponse(
                "UNREADABLE_JSON",
                exception.getClass(),
                ApiChatErrorCode.INVALID_REQUEST,
                "The request body is not valid JSON or contains an invalid field type.",
                jsonParameter(exception),
                ApiChatException.ValidationReason.WRONG_JSON_TYPE,
                request,
                servletResponse);
    }

    @ExceptionHandler(value = HttpMediaTypeNotSupportedException.class,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiChatErrorResponse> unsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        return mappedResponse(
                "UNSUPPORTED_MEDIA_TYPE",
                exception.getClass(),
                ApiChatErrorCode.INVALID_REQUEST,
                "Content-Type must be application/json.",
                null,
                ApiChatException.ValidationReason.WRONG_JSON_TYPE,
                request,
                servletResponse);
    }

    private ResponseEntity<ApiChatErrorResponse> mappedResponse(
            String handler,
            Class<?> exceptionType,
            ApiChatErrorCode code,
            String message,
            String parameter,
            ApiChatException.ValidationReason validationReason,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        boolean committed = servletResponse != null && servletResponse.isCommitted();
        String traceId = safeTraceId(MDC.get("apiChatTraceId"));
        boolean responses = request != null
                && "/v1/responses".equals(request.getRequestURI());
        String safeParameter = responses
                ? ApiResponseDiagnosticParameter.sanitize(parameter)
                : ApiChatDiagnosticParameter.sanitize(parameter);
        String safeValidationReason = validationReason == null
                ? ApiChatException.ValidationReason.UNSPECIFIED.name()
                : validationReason.name();
        if (!"absent".equals(traceId)) {
            if (responses) {
                safeLog(
                        "event=api_responses_error_handler_enter diagnosticSchema=responses-diag-v1 traceId={} handler={} exceptionType={} apiErrorCode={} parameter={} validationReason={} targetStatus={} requestAcceptClass={} committedBeforeMapping={}",
                        traceId, handler, safeType(exceptionType), code.code(),
                        safeParameter, safeValidationReason, code.status(),
                        acceptClass(request), committed);
            } else {
                safeLog(
                        "event=api_chat_error_handler_enter diagnosticSchema=chat-diag-v1 traceId={} handler={} exceptionType={} apiErrorCode={} parameter={} targetStatus={} requestAcceptClass={} committedBeforeMapping={}",
                        traceId, handler, safeType(exceptionType), code.code(),
                        safeParameter, code.status(),
                        acceptClass(request), committed);
            }
        }
        ResponseEntity<ApiChatErrorResponse> result = response(code, message, parameter);
        if (!"absent".equals(traceId)) {
            if (responses) {
                safeLog(
                        "event=api_responses_error_handler_response diagnosticSchema=responses-diag-v1 traceId={} handler={} apiErrorCode={} parameter={} validationReason={} targetStatus={} requestAcceptClass={} responseContentType={} committedBeforeMapping={}",
                        traceId, handler, code.code(),
                        safeParameter, safeValidationReason, code.status(),
                        acceptClass(request),
                        safeContentType(result.getHeaders().getContentType()), committed);
            } else {
                safeLog(
                        "event=api_chat_error_handler_response diagnosticSchema=chat-diag-v1 traceId={} handler={} apiErrorCode={} parameter={} targetStatus={} requestAcceptClass={} responseContentType={} committedBeforeMapping={}",
                        traceId, handler, code.code(),
                        safeParameter, code.status(),
                        acceptClass(request),
                        safeContentType(result.getHeaders().getContentType()), committed);
            }
        }
        return result;
    }

    private static ResponseEntity<ApiChatErrorResponse> response(
            ApiChatErrorCode code,
            String message,
            String parameter) {
        HttpHeaders headers = new HttpHeaders();
        // 无论成功路径选择 SSE 还是 JSON，同步失败都必须明确为 JSON，Worker 才能原样转发客户端错误。
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.noStore().cachePrivate().noTransform());
        headers.set("CDN-Cache-Control", "no-store");
        if (code == ApiChatErrorCode.API_KEY_LIMIT_EXCEEDED
                || code == ApiChatErrorCode.ACCOUNT_LIMIT_EXCEEDED
                || code == ApiChatErrorCode.GLOBAL_LIMIT_EXCEEDED) {
            headers.set(HttpHeaders.RETRY_AFTER, "2");
        }
        ApiChatErrorResponse body = new ApiChatErrorResponse(
                new ApiChatErrorResponse.Error(message, code.type(), parameter, code.code()));
        return new ResponseEntity<>(body, headers, HttpStatusCode.valueOf(code.status()));
    }

    private static String jsonParameter(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getCause();
        if (!(cause instanceof MismatchedInputException mismatch)
                || mismatch.getPath().isEmpty()) {
            return null;
        }
        return mismatch.getPath().get(0).getFieldName();
    }

    private static String acceptClass(HttpServletRequest request) {
        String value = request == null ? null : request.getHeader(HttpHeaders.ACCEPT);
        if (value == null || value.isBlank()) {
            return "ABSENT";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean sse = normalized.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        boolean json = normalized.contains(MediaType.APPLICATION_JSON_VALUE);
        if (sse && json) {
            return "SSE_AND_JSON";
        }
        if (sse) {
            return "SSE_ONLY";
        }
        if (json) {
            return "JSON_ONLY";
        }
        return normalized.contains("*/*") ? "WILDCARD" : "OTHER";
    }

    private static String protocol(HttpServletRequest request) {
        return request != null && "/v1/responses".equals(request.getRequestURI())
                ? "responses" : "chat_completions";
    }

    private static void safeDebug(String template, Object... arguments) {
        try {
            LOGGER.debug(template, arguments);
        } catch (RuntimeException ignored) {
            // 客户端断开日志失败不能重新触发已提交响应的异常处理。
        }
    }

    private static void safeLog(String template, Object... arguments) {
        try {
            LOGGER.warn(template, arguments);
        } catch (RuntimeException ignored) {
            // 日志后端异常不能中断原有 JSON 错误映射。
        }
    }

    private static String safeTraceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "absent";
    }

    private static String safeType(Class<?> type) {
        String value = type == null ? "none" : type.getName();
        return value.matches("[A-Za-z0-9_.$]{1,200}") ? value : "unknown";
    }

    private static String safeContentType(MediaType mediaType) {
        return mediaType == null ? "unknown" : mediaType.toString().toLowerCase(Locale.ROOT);
    }
}
