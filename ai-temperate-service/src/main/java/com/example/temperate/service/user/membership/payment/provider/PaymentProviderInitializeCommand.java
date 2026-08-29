package com.example.temperate.service.user.membership.payment.provider;

import java.time.OffsetDateTime;

/**
 * 该命令是来向支付提供方声明一笔刚创建的本地订单；远程 BAR 实现保持无副作用，本地模拟器据此初始化状态。
 */
public record PaymentProviderInitializeCommand(
        String orderId,
        OffsetDateTime createdAt) {
}
