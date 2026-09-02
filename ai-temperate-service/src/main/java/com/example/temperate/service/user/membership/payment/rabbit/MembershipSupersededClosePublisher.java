package com.example.temperate.service.user.membership.payment.rabbit;

import java.time.Duration;

/**
 * 该发布契约是来把已被新订单替换的第三方支付单提交到异步关单队列，发布失败不得改写或回滚旧单本地终态。
 */
public interface MembershipSupersededClosePublisher {

    void publish(String orderId, int retryCount, Duration delay);
}
