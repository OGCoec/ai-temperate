package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackResumeService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把 REJECTED 回调后的 PENDING/CLOSING 订单重新接回最终 RabbitMQ 检查阶段。
 *
 * <p>Marker 已由回调 Worker 原子释放，恢复消息只等待真实业务边界；发布失败时尚未完成的 callback claim 负责重试。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentRejectedCallbackResumeServiceImpl
        implements MembershipPaymentRejectedCallbackResumeService {

    private final MembershipPaymentFinalCheckScheduler finalCheckScheduler;

    public MembershipPaymentRejectedCallbackResumeServiceImpl(
            MembershipPaymentFinalCheckScheduler finalCheckScheduler) {
        this.finalCheckScheduler = Objects.requireNonNull(finalCheckScheduler);
    }

    @Override
    public void resume(MembershipOrderSnapshot order) {
        Objects.requireNonNull(order);
        if (order.status() == MembershipOrderStatus.PENDING_PAYMENT) {
            finalCheckScheduler.schedulePending(order.orderId(), order.expiresAt());
            return;
        }
        if (order.status() == MembershipOrderStatus.CLOSING) {
            finalCheckScheduler.scheduleClosing(
                    order.orderId(),
                    order.closingDeadlineAt(),
                    0);
        }
    }
}
