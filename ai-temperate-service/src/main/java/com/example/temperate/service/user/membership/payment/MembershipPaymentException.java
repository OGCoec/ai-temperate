package com.example.temperate.service.user.membership.payment;

import java.util.Objects;

/**
 * 该异常是来携带会员支付受控错误码，不暴露订单二进制 ID、平台流水号、回调签名或基础设施细节。
 */
public final class MembershipPaymentException extends RuntimeException {

    private final MembershipPaymentErrorCode code;

    public MembershipPaymentException(
            MembershipPaymentErrorCode code,
            String message) {
        super(message);
        this.code = Objects.requireNonNull(code);
    }

    public MembershipPaymentException(
            MembershipPaymentErrorCode code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
    }

    public MembershipPaymentErrorCode code() {
        return code;
    }
}
