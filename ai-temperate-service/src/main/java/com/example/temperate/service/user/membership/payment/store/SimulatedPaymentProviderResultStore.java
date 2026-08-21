package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 该存储契约是来维护模拟支付方的订单查询结果，支付回调入队脚本会原子地把对应结果改为 PAID。
 */
public interface SimulatedPaymentProviderResultStore {

    void initializeUnpaid(String orderId, OffsetDateTime now);

    void put(SimulatedPaymentProviderResult result);

    Optional<SimulatedPaymentProviderResult> find(String orderId);
}
