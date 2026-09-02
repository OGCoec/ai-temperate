package com.example.temperate.service.user.membership.payment.provider.liuhao.impl;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderInitializeCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoPaymentClient;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把六号易支付客户端适配到统一会员支付接口，并只在既有 provider_trade_no 中封装路由前缀。
 */
@Service("liuhaoMembershipPaymentProvider")
@ConditionalOnProperty(
        prefix = "app.membership-payment.liuhao",
        name = "enabled",
        havingValue = "true")
public final class LiuhaoMembershipPaymentProviderImpl implements MembershipPaymentProvider {

    private final LiuhaoPaymentClient client;

    public LiuhaoMembershipPaymentProviderImpl(LiuhaoPaymentClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.LIUHAO;
    }

    @Override
    public void initializeOrder(PaymentProviderInitializeCommand command) {
        Objects.requireNonNull(command);
        // 本地订单创建阶段既不调用六号，也不保存 Provider 路由或本地订单占位引用。
    }

    @Override
    public PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command) {
        PaymentCheckoutResult result = client.createCheckout(command);
        return new PaymentCheckoutResult(
                tagged(result.providerTradeNo()),
                result.expiresAt(),
                result.created(),
                result.checkoutSubmission());
    }

    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        PaymentCreateResult result = client.createPayment(command);
        return new PaymentCreateResult(
                tagged(result.providerTradeNo()),
                result.providerPayType(),
                result.payInfo(),
                result.created());
    }

    @Override
    public PaymentCreateResult recoverPayment(
            PaymentCreateCommand command,
            String providerTradeNo) {
        PaymentCreateResult result = client.recoverPayment(command, providerTradeNo);
        return new PaymentCreateResult(
                tagged(result.providerTradeNo()),
                result.providerPayType(),
                result.payInfo(),
                result.created());
    }

    @Override
    public PaymentQueryResult queryPayment(PaymentQueryCommand command) {
        PaymentQueryResult result = client.queryPayment(new PaymentQueryCommand(
                command.orderId(), PaymentProviderReference.rawTradeNo(command.providerTradeNo())));
        return new PaymentQueryResult(
                result.orderId(),
                taggedOrExisting(result.providerTradeNo(), command.providerTradeNo()),
                result.channelTradeNo(),
                result.status(),
                result.amountYuan(),
                result.finishedAt(),
                result.callbackId());
    }

    @Override
    public PaymentCloseResult closePayment(PaymentCloseCommand command) {
        PaymentCloseResult result = client.closePayment(new PaymentCloseCommand(
                command.orderId(), PaymentProviderReference.rawTradeNo(command.providerTradeNo())));
        return new PaymentCloseResult(
                result.status(), taggedOrExisting(result.providerTradeNo(), command.providerTradeNo()));
    }

    @Override
    public PaymentRefundResult refundPayment(PaymentRefundCommand command) {
        PaymentRefundResult result = client.refundPayment(new PaymentRefundCommand(
                command.orderId(),
                PaymentProviderReference.rawTradeNo(command.providerTradeNo()),
                command.amountYuan()));
        return new PaymentRefundResult(
                result.status(),
                taggedOrExisting(result.providerTradeNo(), command.providerTradeNo()),
                result.providerRefundNo(),
                result.refundedAmountYuan());
    }

    private static String tagged(String providerTradeNo) {
        return providerTradeNo == null
                ? null
                : PaymentProviderReference.trade(PaymentProviderType.LIUHAO, providerTradeNo);
    }

    private static String taggedOrExisting(String providerTradeNo, String existing) {
        String tagged = tagged(providerTradeNo);
        return tagged == null ? existing : tagged;
    }
}
