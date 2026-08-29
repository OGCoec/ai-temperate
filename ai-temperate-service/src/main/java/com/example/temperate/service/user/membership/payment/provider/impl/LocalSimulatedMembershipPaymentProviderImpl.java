package com.example.temperate.service.user.membership.payment.provider.impl;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderInitializeCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.store.SimulatedPaymentProviderResultStore;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把现有 Redis 本地模拟支付事实适配到统一 Provider 合同，不发送任何外部网络请求。
 */
@Service("localSimulatedMembershipPaymentProvider")
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
public final class LocalSimulatedMembershipPaymentProviderImpl
        implements MembershipPaymentProvider {

    private final SimulatedPaymentProviderResultStore resultStore;
    private final Clock clock;

    public LocalSimulatedMembershipPaymentProviderImpl(
            SimulatedPaymentProviderResultStore resultStore,
            Clock clock) {
        this.resultStore = Objects.requireNonNull(resultStore);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.LOCAL_SIMULATOR;
    }

    @Override
    public void initializeOrder(PaymentProviderInitializeCommand command) {
        // 创建订单阶段只校验边界，不预写模拟 Provider Hash；首次真正查询时才按缺失条件惰性初始化。
        Objects.requireNonNull(command);
    }

    @Override
    public PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command) {
        Objects.requireNonNull(command);
        return new PaymentCheckoutResult(null, null, false, null);
    }

    @Override
    public PaymentQueryResult queryPayment(PaymentQueryCommand command) {
        PaymentQueryCommand value = Objects.requireNonNull(command);
        SimulatedPaymentProviderResult result = resultStore.find(value.orderId()).orElse(null);
        if (result == null) {
            // CREATE_IF_MISSING 不会覆盖先到达的 PAID 回调；初始化后必须再读一次，不能把 Redis 缺失直接解释为未支付。
            resultStore.initializeUnpaid(value.orderId(), MembershipPaymentTime.now(clock));
            result = resultStore.find(value.orderId()).orElse(null);
            if (result == null) {
                return PaymentQueryResult.unknown(value.orderId());
            }
        }
        // 本地模拟结果只保存可恢复的 callbackId，不伪造 BAR 才具有的渠道流水号；
        // PAID 结果的 updatedAt 由回调 paidAt 写入，可作为统一 Provider 合同的完成时间。
        return new PaymentQueryResult(
                result.orderId(),
                result.providerTradeNo(),
                null,
                mapStatus(result.status()),
                result.paidAmountYuan(),
                result.updatedAt(),
                result.callbackId());
    }

    @Override
    public PaymentCloseResult closePayment(PaymentCloseCommand command) {
        PaymentCloseCommand value = Objects.requireNonNull(command);
        PaymentQueryResult current = queryPayment(
                new PaymentQueryCommand(value.orderId(), value.providerTradeNo()));
        PaymentProviderStatus status = switch (current.status()) {
            case PENDING -> PaymentProviderStatus.CLOSED;
            case PAID -> PaymentProviderStatus.PAID;
            default -> current.status();
        };
        return new PaymentCloseResult(status, current.providerTradeNo());
    }

    @Override
    public PaymentRefundResult refundPayment(PaymentRefundCommand command) {
        PaymentRefundCommand value = Objects.requireNonNull(command);
        return new PaymentRefundResult(
                PaymentProviderStatus.REFUNDED,
                value.providerTradeNo(),
                "LOCAL-SIMULATED-REFUND",
                value.amountYuan());
    }

    private static PaymentProviderStatus mapStatus(SimulatedPaymentProviderStatus status) {
        if (status == null) {
            return PaymentProviderStatus.UNKNOWN;
        }
        return switch (status) {
            case UNPAID -> PaymentProviderStatus.PENDING;
            case PAID -> PaymentProviderStatus.PAID;
            case UNKNOWN -> PaymentProviderStatus.UNKNOWN;
        };
    }
}
