package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
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
 * 该处理器是来保证模拟支付回调的所有受控失败只返回精确纯文本 fail，并区分 400、401、415 和 Redis 503。
 */
@RestControllerAdvice(assignableTypes = SimulatedLiuhaoPaymentCallbackController.class)
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class SimulatedPaymentCallbackExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SimulatedPaymentCallbackExceptionHandler.class);
    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType(
            "text", "plain", StandardCharsets.UTF_8);

    private final MembershipPaymentMetrics metrics;

    public SimulatedPaymentCallbackExceptionHandler(MembershipPaymentMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(SimulatedPaymentCallbackTransportException.class)
    public ResponseEntity<String> handleTransport(
            SimulatedPaymentCallbackTransportException exception) {
        metrics.callbackRejected();
        HttpStatus status = switch (exception.kind()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        };
        return fail(status);
    }

    @ExceptionHandler(MembershipPaymentException.class)
    public ResponseEntity<String> handleBusiness(MembershipPaymentException exception) {
        HttpStatus status = exception.code()
                        == MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_REQUEST;
        return fail(status);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleUnexpected(RuntimeException exception) {
        LOGGER.warn(
                "Simulated membership payment callback failed; traceId={} reason={}",
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
