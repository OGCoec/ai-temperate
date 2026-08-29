package com.example.temperate.service.user.membership.payment.provider.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentStatusQueryService;
import com.example.temperate.service.user.membership.payment.store.SimulatedPaymentProviderResultStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来读取 Redis 中的模拟平台事实；缺失结果明确返回 UNKNOWN，不访问 PostgreSQL 或猜测 UNPAID。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
public final class SimulatedPaymentStatusQueryServiceImpl
        implements SimulatedPaymentStatusQueryService {

    private final SimulatedPaymentProviderResultStore resultStore;
    private final Clock clock;

    public SimulatedPaymentStatusQueryServiceImpl(
            SimulatedPaymentProviderResultStore resultStore,
            Clock clock) {
        this.resultStore = Objects.requireNonNull(resultStore);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SimulatedPaymentProviderResult query(String orderId) {
        String validOrderId = new MembershipOrderRedisId(orderId).value();
        return resultStore.find(validOrderId).orElseGet(() ->
                new SimulatedPaymentProviderResult(
                        SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                        validOrderId,
                        SimulatedPaymentProviderStatus.UNKNOWN,
                        null,
                        null,
                        null,
                        null,
                        MembershipPaymentTime.now(clock)));
    }
}
