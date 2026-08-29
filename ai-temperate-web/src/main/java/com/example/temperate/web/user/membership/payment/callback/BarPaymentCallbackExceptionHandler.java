package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 该处理器是来保证 BAR 回调失败时绝不误返回 success，并用无敏感字段纯文本响应区分鉴权、请求和临时故障。
 */
@RestControllerAdvice(assignableTypes = BarPaymentCallbackController.class)
@ConditionalOnProperty(
        prefix = "app.membership-payment.bar",
        name = "enabled",
        havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class BarPaymentCallbackExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(BarPaymentCallbackExceptionHandler.class);
    private static final MediaType TEXT_PLAIN_UTF8 =
            new MediaType("text", "plain", StandardCharsets.UTF_8);

    @ExceptionHandler(BarPaymentCallbackTransportException.class)
    public ResponseEntity<String> handleTransport(
            BarPaymentCallbackTransportException exception) {
        return fail(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MembershipPaymentException.class)
    public ResponseEntity<String> handleBusiness(MembershipPaymentException exception) {
        HttpStatus status = switch (exception.code()) {
            case BAR_AUTH_FAILED, BAR_SIGNATURE_INVALID -> HttpStatus.UNAUTHORIZED;
            case INPUT_INVALID, MEMBERSHIP_ORDER_NOT_FOUND, BAR_ORDER_CONFLICT ->
                    HttpStatus.BAD_REQUEST;
            case BAR_RESPONSE_INVALID -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return fail(status);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleUnexpected(RuntimeException exception) {
        LOGGER.warn(
                "BAR membership payment callback failed; traceId={} reason={}",
                MembershipPaymentTraceContext.currentTraceId(),
                exception.getClass().getSimpleName());
        return fail(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static ResponseEntity<String> fail(HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(TEXT_PLAIN_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("CDN-Cache-Control", "no-store")
                .body("fail");
    }
}
