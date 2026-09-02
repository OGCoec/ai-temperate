package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
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
 * 该处理器是来保证六号回调失败时不返回 success，并以固定拒绝原因记录指标和无敏感内容的纯文本状态。
 */
@RestControllerAdvice(assignableTypes = LiuhaoPaymentCallbackController.class)
@ConditionalOnProperty(
        prefix = "app.membership-payment.liuhao",
        name = "enabled",
        havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LiuhaoPaymentCallbackExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LiuhaoPaymentCallbackExceptionHandler.class);
    private static final MediaType TEXT_PLAIN_UTF8 =
            new MediaType("text", "plain", StandardCharsets.UTF_8);
    private final MembershipPaymentMetrics metrics;

    public LiuhaoPaymentCallbackExceptionHandler(MembershipPaymentMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
    }

    @ExceptionHandler(LiuhaoPaymentCallbackTransportException.class)
    public ResponseEntity<String> handleTransport(
            LiuhaoPaymentCallbackTransportException exception) {
        // 外部输入只映射为固定枚举标签，禁止把 Query、签名或买家标识写入日志和指标。
        String reason = exception.reason().name().toLowerCase(Locale.ROOT);
        metrics.callbackTransportRejected(reason);
        LOGGER.warn(
                "Liuhao membership payment callback transport rejected; traceId={} reason={} status={}",
                MembershipPaymentTraceContext.currentTraceId(),
                reason,
                HttpStatus.BAD_REQUEST.value());
        return fail(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MembershipPaymentException.class)
    public ResponseEntity<String> handleBusiness(MembershipPaymentException exception) {
        HttpStatus status = switch (exception.code()) {
            case LIUHAO_AUTH_FAILED, LIUHAO_SIGNATURE_INVALID -> HttpStatus.UNAUTHORIZED;
            case INPUT_INVALID, MEMBERSHIP_ORDER_NOT_FOUND, LIUHAO_ORDER_CONFLICT ->
                    HttpStatus.BAD_REQUEST;
            case LIUHAO_RESPONSE_INVALID -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        LOGGER.warn(
                "Liuhao membership payment callback rejected; traceId={} reason={} status={}",
                MembershipPaymentTraceContext.currentTraceId(),
                exception.code().name().toLowerCase(Locale.ROOT),
                status.value());
        return fail(status);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleUnexpected(RuntimeException exception) {
        LOGGER.warn(
                "Liuhao membership payment callback failed; traceId={} reason={}",
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
