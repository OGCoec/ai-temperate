package com.example.temperate.service.user.membership.payment.provider.bar.impl;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderInitializeCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentClient;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把 BAR 的真实 HTTPS 客户端及其短时签名提交描述适配到统一会员支付 Provider，并保持订单创建阶段零外部请求。
 */
@Service("barMembershipPaymentProvider")
@ConditionalOnProperty(
        prefix = "app.membership-payment.bar",
        name = "enabled",
        havingValue = "true")
public final class BarMembershipPaymentProviderImpl
        implements MembershipPaymentProvider {

    private final BarPaymentClient client;

    public BarMembershipPaymentProviderImpl(BarPaymentClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.BAR;
    }

    @Override
    public void initializeOrder(PaymentProviderInitializeCommand command) {
        Objects.requireNonNull(command);
        // BAR 订单只在用户发起 payment-attempts 后创建，普通会员订单创建不得触发外部副作用。
    }

    @Override
    public PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command) {
        return client.createCheckout(command);
    }

    @Override
    public PaymentQueryResult queryPayment(PaymentQueryCommand command) {
        return client.queryPayment(command);
    }

    @Override
    public PaymentCloseResult closePayment(PaymentCloseCommand command) {
        return client.closePayment(command);
    }

    @Override
    public PaymentRefundResult refundPayment(PaymentRefundCommand command) {
        return client.refundPayment(command);
    }
}
