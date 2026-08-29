package com.example.temperate.service.user.membership.payment.rabbit;

import java.time.OffsetDateTime;

/**
 * 该调度契约是来把新订单直接发布到 PENDING/CLOSING 最终业务边界，同时隐藏阶段下标和零延迟计算细节。
 */
public interface MembershipPaymentFinalCheckScheduler {

    void schedulePending(String orderId, OffsetDateTime expiresAt);

    void scheduleClosing(
            String orderId,
            OffsetDateTime hardCloseAt,
            int terminalRetryCount);
}
