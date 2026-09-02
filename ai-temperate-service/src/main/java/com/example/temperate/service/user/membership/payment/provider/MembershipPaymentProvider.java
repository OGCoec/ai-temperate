package com.example.temperate.service.user.membership.payment.provider;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;

/**
 * 该策略接口是来统一会员支付创建、查询、关闭和退款边界，使业务状态机不依赖具体平台协议。
 */
public interface MembershipPaymentProvider {

    PaymentProviderType type();

    void initializeOrder(PaymentProviderInitializeCommand command);

    PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command);

    PaymentCreateResult createPayment(PaymentCreateCommand command);

    /**
     * 恢复已经发起但没有支付入口的订单；恢复只允许查询并重建入口，禁止再次创建第三方订单。
     * 默认实现用于 BAR 和本地模拟器，明确拒绝不支持的恢复能力。
     */
    default PaymentCreateResult recoverPayment(
            PaymentCreateCommand command,
            String providerTradeNo) {
        throw new MembershipPaymentException(
                MembershipPaymentErrorCode.PAYMENT_CREATE_OUTCOME_UNKNOWN,
                "The existing external payment result is still being confirmed.");
    }

    PaymentQueryResult queryPayment(PaymentQueryCommand command);

    PaymentCloseResult closePayment(PaymentCloseCommand command);

    PaymentRefundResult refundPayment(PaymentRefundCommand command);
}
