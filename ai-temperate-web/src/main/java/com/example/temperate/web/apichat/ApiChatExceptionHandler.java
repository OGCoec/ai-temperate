package com.example.temperate.web.apichat;

import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.web.apikey.ApiChatBodyLimitFilter.PayloadTooLargeException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 该异常处理器是来把 `/v1/chat/completions` 在 SSE 响应开始前发生的校验、额度和并发错误转换为 OpenAI JSON，且不暴露 Jackson 或上游异常细节。
 */
@Order(-100)
@RestControllerAdvice(assignableTypes = ApiChatCompletionController.class)
public final class ApiChatExceptionHandler {

    @ExceptionHandler(ApiChatException.class)
    public ResponseEntity<ApiChatErrorResponse> handle(ApiChatException exception) {
        return response(exception.code(), exception.getMessage(), exception.parameter());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiChatErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ApiChatException apiChatException) {
                return handle(apiChatException);
            }
            if (current instanceof PayloadTooLargeException) {
                return response(
                        ApiChatErrorCode.INVALID_REQUEST,
                        "The request body is too large.",
                        null);
            }
            current = current.getCause();
        }
        return response(
                ApiChatErrorCode.INVALID_REQUEST,
                "The request body is not valid JSON or contains an invalid field type.",
                jsonParameter(exception));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiChatErrorResponse> unsupportedMediaType() {
        return response(
                ApiChatErrorCode.INVALID_REQUEST,
                "Content-Type must be application/json.",
                null);
    }

    private static ResponseEntity<ApiChatErrorResponse> response(
            ApiChatErrorCode code,
            String message,
            String parameter) {
        HttpHeaders headers = new HttpHeaders();
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
}
