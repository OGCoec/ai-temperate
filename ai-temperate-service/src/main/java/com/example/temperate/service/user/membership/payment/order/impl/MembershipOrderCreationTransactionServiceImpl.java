package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderReplacementCommand;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来在单个 PostgreSQL 本地事务中创建会员订单，并串行化同一用户的活动订单竞争与 UUIDv4 幂等重放。
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
     * 单条 CTE 在用户 advisory lock 后依次解析幂等订单、活动订单与新插入事实；极少数跨用户唯一键竞争才执行回退查询。
     */
    @Override
    @Transactional
    public MembershipOrderCreationResult createOrGet(MembershipOrder candidate) {
        MembershipOrder requested = Objects.requireNonNull(candidate, "candidate must not be null");
        MembershipOrder resolved = membershipOrderMapper.createOrResolve(requested);
        if (resolved != null && Arrays.equals(requested.getId(), resolved.getId())) {
            return new MembershipOrderCreationResult(requested, true);
        }
        if (resolved != null
                && Objects.equals(
                        requested.getIdempotencyKey(), resolved.getIdempotencyKey())) {
            return existingResult(requested, resolved);
        }
        if (resolved != null) {
            return activeOrderResult(requested, resolved);
        }

        // 同语句快照看不到由其他事务刚提交且触发 ON CONFLICT 的跨用户胜出行；只在空返回时执行两次精确索引回退。
        MembershipOrder winner = membershipOrderMapper.findByIdempotencyKey(
                requested.getIdempotencyKey());
        if (winner != null) {
            return existingResult(requested, winner);
        }
        MembershipOrder active = membershipOrderMapper.findActiveByLoginIdentityId(
                requested.getLoginIdentityId());
        if (active != null) {
            return activeOrderResult(requested, active);
        }
        throw new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                "The login identity no longer exists or the order winner cannot be resolved.");
    }

    /**
     * Redis 先形成回调可见终态，本事务再锁定用户活动订单并完成旧单落库与新单插入；已有 REFUND_REQUIRED 必须原样保留。
     */
    @Override
    @Transactional
    public MembershipOrderCreationResult replaceActive(
            MembershipOrderReplacementCommand command) {
        MembershipOrderReplacementCommand valid = Objects.requireNonNull(command);
        MembershipOrder candidate = valid.candidate();
        long loginIdentityId = Objects.requireNonNull(candidate.getLoginIdentityId());
        membershipOrderMapper.acquireCreationLock(loginIdentityId);

        MembershipOrder idempotent = membershipOrderMapper.findByIdempotencyKey(
                candidate.getIdempotencyKey());
        if (idempotent != null) {
            return existingResult(candidate, idempotent);
        }

        MembershipOrder active = membershipOrderMapper.findActiveByLoginIdentityId(
                loginIdentityId);
        if (active != null) {
            if (!Arrays.equals(active.getId(), valid.replacedOrderId())) {
                throw activeOrderConflict();
            }
            requireSafeTerminalStatus(active, valid.terminalStatus());
            int updated = membershipOrderMapper.supersedeActiveForReplacement(
                    valid.replacedOrderId(),
                    loginIdentityId,
                    valid.terminalStatus(),
                    valid.terminalStateVersion(),
                    valid.changedAt());
            if (updated != 1) {
                throw activeOrderConflict();
            }
        } else {
            MembershipOrder replaced = membershipOrderMapper.findById(
                    valid.replacedOrderId());
            if (replaced == null
                    || !Objects.equals(replaced.getLoginIdentityId(), loginIdentityId)
                    || !replaced.getStatus().terminal()) {
                throw activeOrderConflict();
            }
        }

        if (membershipOrderMapper.insert(candidate) == 1) {
            return new MembershipOrderCreationResult(candidate, true);
        }
        MembershipOrder winner = membershipOrderMapper.findByIdempotencyKey(
                candidate.getIdempotencyKey());
        if (winner != null) {
            return existingResult(candidate, winner);
        }
        throw new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                "The replacement membership order could not be created.");
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

    private static MembershipOrderCreationResult activeOrderResult(
            MembershipOrder requested,
            MembershipOrder active) {
        // 同键胜出订单可能恰好在前一次幂等查询与活动订单查询之间提交；此时必须识别为合法重放，不能误报第二笔活动订单冲突。
        if (Objects.equals(requested.getIdempotencyKey(), active.getIdempotencyKey())) {
            return existingResult(requested, active);
        }
        return new MembershipOrderCreationResult(active, false, true);
    }

    private static void requireSafeTerminalStatus(
            MembershipOrder active,
            MembershipOrderStatus terminalStatus) {
        if (active.getStatus() != MembershipOrderStatus.PENDING_PAYMENT
                && active.getStatus() != MembershipOrderStatus.CLOSING) {
            throw activeOrderConflict();
        }
        boolean externalPaymentStarted = active.getStatus() == MembershipOrderStatus.CLOSING
                || active.getPaymentStartedAt() != null
                || active.getProviderTradeNo() != null;
        // Redis 的支付发起事实可能领先数据库刷盘，因此 CLOSED 是安全的保守裁决；数据库已有支付证据时则绝不能降为 CANCELLED。
        if (externalPaymentStarted && terminalStatus != MembershipOrderStatus.CLOSED) {
            throw activeOrderConflict();
        }
    }

    private static MembershipPaymentException activeOrderConflict() {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                "The login identity already has an active membership order.");
    }
}
