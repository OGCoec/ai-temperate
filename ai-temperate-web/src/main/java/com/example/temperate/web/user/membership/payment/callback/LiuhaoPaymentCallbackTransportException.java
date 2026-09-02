package com.example.temperate.web.user.membership.payment.callback;

import java.util.Objects;

/** 该异常是来用固定低基数原因拒绝六号回调的非法 Query 结构，且不携带任何原始参数值。 */
public final class LiuhaoPaymentCallbackTransportException extends RuntimeException {

    private final Reason reason;

    public LiuhaoPaymentCallbackTransportException(Reason reason) {
        super("Liuhao callback transport rejected: "
                + Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 该枚举是来限制传输拒绝的日志和指标标签集合，禁止引入参数名、参数值或其他高基数字符串。 */
    public enum Reason {
        MISSING_REQUIRED,
        TOO_MANY_PARAMETERS,
        INVALID_PARAMETER_NAME,
        REPEATED_PARAMETER,
        INVALID_PARAMETER_VALUE,
        VALUE_TOO_LARGE,
        PAYLOAD_TOO_LARGE
    }
}
