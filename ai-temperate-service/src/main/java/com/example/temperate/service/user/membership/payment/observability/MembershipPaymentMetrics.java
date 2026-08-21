package com.example.temperate.service.user.membership.payment.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该组件是来集中注册会员支付闭环的低基数计数器和队列大小 Gauge，禁止添加订单、回调、流水或用户标签。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentMetrics {

    private final Counter callbackReceived;
    private final Counter callbackDuplicate;
    private final Counter callbackRejected;
    private final Counter callbackRecovered;
    private final Counter orderPersisted;
    private final Counter orderPersistFailure;
    private final Counter paymentCheck;
    private final Counter paymentQuery;
    private final Counter closing;
    private final Counter latePaid;
    private final Counter refundRequired;
    private final Counter dlq;
    private final AtomicLong callbackProcessingSize = new AtomicLong();
    private final AtomicLong orderDirtySize = new AtomicLong();
    private final AtomicLong orderProcessingSize = new AtomicLong();

    public MembershipPaymentMetrics(MeterRegistry meterRegistry) {
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        callbackReceived = registry.counter("membership_payment_callback_received_total");
        callbackDuplicate = registry.counter("membership_payment_callback_duplicate_total");
        callbackRejected = registry.counter("membership_payment_callback_rejected_total");
        callbackRecovered = registry.counter("membership_payment_callback_recovered_total");
        orderPersisted = registry.counter("membership_order_persisted_total");
        orderPersistFailure = registry.counter("membership_order_persist_failure_total");
        paymentCheck = registry.counter("membership_payment_check_total");
        paymentQuery = registry.counter("membership_payment_query_total");
        closing = registry.counter("membership_payment_closing_total");
        latePaid = registry.counter("membership_payment_late_paid_total");
        refundRequired = registry.counter("membership_payment_refund_required_total");
        dlq = registry.counter("membership_payment_dlq_total");
        Gauge.builder(
                        "membership_payment_callback_processing_size",
                        callbackProcessingSize,
                        AtomicLong::doubleValue)
                .register(registry);
        Gauge.builder(
                        "membership_order_dirty_size",
                        orderDirtySize,
                        AtomicLong::doubleValue)
                .register(registry);
        Gauge.builder(
                        "membership_order_processing_size",
                        orderProcessingSize,
                        AtomicLong::doubleValue)
                .register(registry);
    }

    public void callbackReceived(boolean duplicate) {
        callbackReceived.increment();
        if (duplicate) {
            callbackDuplicate.increment();
        }
    }

    public void callbackRejected() {
        callbackRejected.increment();
    }

    public void callbackRejected(int count) {
        if (count > 0) {
            callbackRejected.increment(count);
        }
    }

    public void callbackRecovered(int count) {
        if (count > 0) {
            callbackRecovered.increment(count);
        }
    }

    public void callbackProcessingSize(long size) {
        callbackProcessingSize.set(Math.max(0L, size));
    }

    public void orderQueueSizes(long dirty, long processing) {
        orderDirtySize.set(Math.max(0L, dirty));
        orderProcessingSize.set(Math.max(0L, processing));
    }

    public void orderPersisted(int count) {
        if (count > 0) {
            orderPersisted.increment(count);
        }
    }

    public void orderPersistFailure() {
        orderPersistFailure.increment();
    }

    public void paymentCheck() {
        paymentCheck.increment();
    }

    public void paymentQuery() {
        paymentQuery.increment();
    }

    public void closing() {
        closing.increment();
    }

    public void latePaid() {
        latePaid.increment();
    }

    public void refundRequired() {
        refundRequired.increment();
    }

    public void dlq() {
        dlq.increment();
    }
}
