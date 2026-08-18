package com.example.temperate.web.apichat;

import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticParameter;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticParameter;
import com.example.temperate.web.apikey.ApiInferenceBodyLimitFilter.PayloadTooLargeException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 该异常处理器是来把公开 Chat 与 Responses 在响应提交前的校验、额度和并发错误转换为 OpenAI JSON，且不暴露 Jackson 或上游异常细节。
 */
@Order(-100)
@RestControllerAdvice(assignableTypes = {
        ApiChatCompletionController.class,
        com.example.temperate.web.apiresponse.ApiResponsesController.class})
public final class ApiChatExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApiChatExceptionHandler.class);

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
        return new ResponseEntity<>(body, headers, HttpStatus.valueOf(code.status()));
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
