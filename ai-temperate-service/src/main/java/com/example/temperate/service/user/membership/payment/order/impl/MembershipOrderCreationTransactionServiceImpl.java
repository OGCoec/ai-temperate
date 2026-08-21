package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationTransactionService;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来在单个 PostgreSQL 本地事务中创建会员订单，并在 UUIDv4 唯一约束竞争后解析唯一胜出记录。
 *
 * <p>事务边界只覆盖数据库读写；Redis 快照和 RabbitMQ 消息必须在本方法提交成功后由编排服务执行。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderCreationTransactionServiceImpl
        implements MembershipOrderCreationTransactionService {

    private final MembershipOrderMapper membershipOrderMapper;

    public MembershipOrderCreationTransactionServiceImpl(
            MembershipOrderMapper membershipOrderMapper) {
        this.membershipOrderMapper = Objects.requireNonNull(membershipOrderMapper);
    }

    /**
     * 先读取已有幂等订单，再尝试无异常冲突插入；并发失败时重新读取数据库唯一约束选出的胜出记录。
     */
    @Override
    @Transactional
    public MembershipOrderCreationResult createOrGet(MembershipOrder candidate) {
        MembershipOrder requested = Objects.requireNonNull(candidate, "candidate must not be null");
        MembershipOrder existing = membershipOrderMapper.findByIdempotencyKey(
                requested.getIdempotencyKey());
        if (existing != null) {
            return existingResult(requested, existing);
        }
        if (membershipOrderMapper.insert(requested) == 1) {
            return new MembershipOrderCreationResult(requested, true);
        }

        // ON CONFLICT 只说明另一事务赢得唯一键；必须重新读取并校验业务意图，不能把不同用户的 UUID 当作合法重放。
        MembershipOrder winner = membershipOrderMapper.findByIdempotencyKey(
                requested.getIdempotencyKey());
        if (winner == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                    "The login identity no longer exists or the order winner cannot be resolved.");
        }
        return existingResult(requested, winner);
    }

    private static MembershipOrderCreationResult existingResult(
            MembershipOrder requested,
            MembershipOrder existing) {
        if (!Objects.equals(requested.getLoginIdentityId(), existing.getLoginIdentityId())
                || requested.getMembershipTier() != existing.getMembershipTier()
                || !Objects.equals(requested.getPayType(), existing.getPayType())) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_IDEMPOTENCY_CONFLICT,
                    "The idempotency key is already bound to another membership order intent.");
        }
        return new MembershipOrderCreationResult(existing, false);
    }
}
