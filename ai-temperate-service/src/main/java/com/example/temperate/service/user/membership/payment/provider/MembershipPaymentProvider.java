package com.example.temperate.service.user.membership.payment.provider;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;

/**
 * 该策略接口是来统一会员支付创建、查询、关闭和退款边界，使业务状态机不依赖具体平台协议。
 */
public interface MembershipPaymentProvider {

    PaymentProviderType type();

    void initializeOrder(PaymentProviderInitializeCommand command);

    PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command);

    PaymentQueryResult queryPayment(PaymentQueryCommand command);

    PaymentCloseResult closePayment(PaymentCloseCommand command);

    PaymentRefundResult refundPayment(PaymentRefundCommand command);
}
