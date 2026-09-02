package com.example.temperate.service.user.membership.payment;

import java.util.Objects;

/**
 * 该异常是来携带会员支付受控错误码和仅供内部收敛使用的交易号证据，不暴露订单二进制 ID、回调签名或基础设施细节。
 */
public final class MembershipPaymentException extends RuntimeException {

    private final MembershipPaymentErrorCode code;
    private final String providerTradeNo;

    public MembershipPaymentException(
            MembershipPaymentErrorCode code,
            String message) {
        super(message);
        this.code = Objects.requireNonNull(code);
        this.providerTradeNo = null;
    }

    public MembershipPaymentException(
            MembershipPaymentErrorCode code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
        this.providerTradeNo = null;
    }

    /**
     * 创建已被 Provider 接受但入口不可用时携带交易号，让业务层先完成绑定再返回受控错误；该值禁止进入响应或日志。
     */
    public MembershipPaymentException(
            MembershipPaymentErrorCode code,
            String message,
            String providerTradeNo) {
        super(message);
        this.code = Objects.requireNonNull(code);
        this.providerTradeNo = providerTradeNo;
    }

    public MembershipPaymentException(
            MembershipPaymentErrorCode code,
            String message,
            Throwable cause,
            String providerTradeNo) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
        this.providerTradeNo = providerTradeNo;
    }

    public MembershipPaymentErrorCode code() {
        return code;
    }

    /** 返回仅供内部绑定与补偿使用的交易号证据；调用方不得把它写入日志或外部响应。 */
    public String providerTradeNo() {
        return providerTradeNo;
    }
}
