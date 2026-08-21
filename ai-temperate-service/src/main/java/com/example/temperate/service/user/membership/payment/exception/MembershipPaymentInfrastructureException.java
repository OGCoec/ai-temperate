package com.example.temperate.service.user.membership.payment.exception;

/**
 * 该异常是来统一表示会员支付 Redis、RabbitMQ 或其他基础设施暂时不可用，供 Web 层转换为可重试的受控响应。
 */
public final class MembershipPaymentInfrastructureException extends RuntimeException {

    public MembershipPaymentInfrastructureException(String message) {
        super(message);
    }

    public MembershipPaymentInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
