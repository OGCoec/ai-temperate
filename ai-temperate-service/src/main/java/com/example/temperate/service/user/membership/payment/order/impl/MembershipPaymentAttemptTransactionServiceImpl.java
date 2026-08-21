package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptDatabaseResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来通过条件 UPDATE RETURNING 原子记录首次支付发起，并在并发落败后读取数据库胜出事实。
 *
 * <p>本事务只负责 PostgreSQL；Redis 刷新必须在本方法提交返回后由编排服务执行。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentAttemptTransactionServiceImpl
        implements MembershipPaymentAttemptTransactionService {

    private final MembershipOrderMapper orderMapper;

    public MembershipPaymentAttemptTransactionServiceImpl(MembershipOrderMapper orderMapper) {
        this.orderMapper = Objects.requireNonNull(orderMapper);
    }

    /**
     * 更新条件同时裁决归属、状态和过期边界；并发未更新时只接受仍在有效期内的既有发起事实。
     */
    @Override
    @Transactional
    public MembershipPaymentAttemptDatabaseResult startOrGet(
            long loginIdentityId,
            byte[] orderId,
            OffsetDateTime attemptedAt) {
        OffsetDateTime now = Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");
        MembershipOrder started = orderMapper.startPaymentAttemptIfAbsent(
                orderId,
                loginIdentityId,
                MembershipOrderStatus.PENDING_PAYMENT,
                now);
        if (started != null) {
            return new MembershipPaymentAttemptDatabaseResult(started, true);
        }

        // 条件更新落败可能是并发胜出，也可能是越权、过期或状态迁移；必须读取数据库事实后精确裁决。
        MembershipOrder persisted = orderMapper.findOwnedById(orderId, loginIdentityId);
        if (persisted == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                    "The membership order was not found.");
        }
        if (persisted.getStatus() == MembershipOrderStatus.PENDING_PAYMENT
                && persisted.getPaymentStartedAt() != null
                && persisted.getExpiresAt() != null
                && now.isBefore(persisted.getExpiresAt())) {
            return new MembershipPaymentAttemptDatabaseResult(persisted, false);
        }
        throw new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                "The membership order no longer allows payment to start.");
    }
}
