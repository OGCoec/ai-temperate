package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该服务是来主动查询模拟支付方的三值订单事实，UNKNOWN 必须由调用方重试而不能等同于未支付。
 */
public interface SimulatedPaymentStatusQueryService {

    SimulatedPaymentProviderResult query(String orderId);
}
