package com.example.temperate.web.user.membership.payment;

import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 该处理器是来把会员套餐报价和订单受控异常映射为稳定 HTTP 响应，并隐藏订单归属与基础设施细节。
 */
@RestControllerAdvice(assignableTypes = {
        CurrentUserMembershipOrderController.class,
        CurrentUserMembershipPlanOfferController.class
})
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MembershipPaymentExceptionHandler {

    private final Clock clock;

    public MembershipPaymentExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @ExceptionHandler(MembershipPaymentException.class)
    public ResponseEntity<ApiErrorResponse> handle(MembershipPaymentException exception) {
        HttpStatus status = switch (exception.code()) {
            case MEMBERSHIP_ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MEMBERSHIP_ORDER_IDEMPOTENCY_CONFLICT,
                    MEMBERSHIP_ORDER_STATE_CONFLICT,
                    MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS,
                    MEMBERSHIP_TRANSITION_REJECTED,
                    MEMBERSHIP_UPGRADE_HISTORY_MISSING,
                    MEMBERSHIP_PAYMENT_AMOUNT_MISMATCH,
                    MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                    BAR_ORDER_CONFLICT,
                    LIUHAO_ORDER_CONFLICT,
                    LIUHAO_CHECKOUT_UNAVAILABLE,
                    LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE,
                    PAYMENT_CREATE_OUTCOME_UNKNOWN -> HttpStatus.CONFLICT;
            case BAR_TIMEOUT, LIUHAO_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BAR_AUTH_FAILED,
                    BAR_RESPONSE_INVALID,
                    BAR_SIGNATURE_INVALID,
                    LIUHAO_AUTH_FAILED,
                    LIUHAO_RESPONSE_INVALID,
                    LIUHAO_SIGNATURE_INVALID -> HttpStatus.BAD_GATEWAY;
            case FEATURE_DISABLED,
                    PAYMENT_CHECKOUT_DISABLED,
                    PAYMENT_PROVIDER_UNSUPPORTED,
                    BAR_UNAVAILABLE,
                    LIUHAO_UNAVAILABLE,
                    LIUHAO_CLIENT_IP_UNAVAILABLE,
                    LIUHAO_CREATE_OUTCOME_UNKNOWN,
                    MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INPUT_INVALID -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("CDN-Cache-Control", "no-store")
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        message(exception.code()),
                        clock.instant()));
    }

    private static String message(MembershipPaymentErrorCode code) {
        return switch (code) {
            case MEMBERSHIP_ORDER_NOT_FOUND -> "会员支付订单不存在。";
            case MEMBERSHIP_ORDER_IDEMPOTENCY_CONFLICT -> "该幂等键已用于另一会员订单意图。";
            case MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS -> "支付结果正在处理，请稍后重试当前操作。";
            case MEMBERSHIP_TRANSITION_REJECTED -> "当前会员等级不允许执行该转换。";
            case MEMBERSHIP_UPGRADE_HISTORY_MISSING -> "缺少可信的历史支付周期，暂时无法计算升级价格。";
            case MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE -> "会员支付状态暂时不可用，请稍后重试。";
            case MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE -> "会员支付检查暂时无法提交，请原样重试。";
            case PAYMENT_CHECKOUT_DISABLED -> "会员支付发起已暂停，请稍后重试。";
            case PAYMENT_PROVIDER_UNSUPPORTED -> "当前支付提供方暂不可用。";
            case BAR_TIMEOUT -> "BAR 响应超时，支付结果正在确认，请勿重复下单。";
            case BAR_UNAVAILABLE -> "BAR 请求结果暂时不明确，正在确认，请勿重复下单。";
            case BAR_AUTH_FAILED,
                    BAR_RESPONSE_INVALID,
                    BAR_SIGNATURE_INVALID -> "BAR 沙箱返回了无法信任的响应。";
            case BAR_ORDER_CONFLICT -> "BAR 沙箱订单与当前订单冲突。";
            case LIUHAO_TIMEOUT -> "六号易支付响应超时，支付结果正在确认，请勿重复下单。";
            case LIUHAO_UNAVAILABLE -> "六号易支付请求结果暂时不明确，正在确认，请勿重复下单。";
            case LIUHAO_AUTH_FAILED,
                    LIUHAO_RESPONSE_INVALID,
                    LIUHAO_SIGNATURE_INVALID -> "六号易支付返回了无法信任的响应。";
            case LIUHAO_CHECKOUT_UNAVAILABLE -> "六号易支付已创建订单，但返回的支付入口暂时无法安全打开，请勿重复下单。";
            case LIUHAO_ORDER_CONFLICT -> "六号易支付订单与当前订单冲突。";
            case LIUHAO_CLIENT_IP_UNAVAILABLE -> "暂时无法确认可信客户端地址，请稍后重试。";
            case LIUHAO_CREATE_OUTCOME_UNKNOWN -> "六号支付下单结果暂时无法确认，请不要重复支付。";
            case LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE -> "原支付入口无法安全重放，请先关闭旧订单再重新创建。";
            case PAYMENT_CREATE_OUTCOME_UNKNOWN -> "支付订单已经发起，第三方结果正在确认，请勿切换提供方或重复下单。";
            case FEATURE_DISABLED -> "会员支付功能暂未启用。";
            case INPUT_INVALID -> "会员支付请求参数无效。";
            default -> "会员支付订单状态冲突，请刷新后重试。";
        };
    }
}
