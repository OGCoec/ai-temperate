package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipClosingFinalizationSource;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderRealtimeGuard;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderPaidCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 该存储契约是来读取会员订单 Redis 快照并通过原子状态机执行支付、取消和关单迁移。
 */
public interface MembershipOrderSnapshotStore {

    void put(MembershipOrderSnapshot snapshot);

    void putAll(Collection<MembershipOrderSnapshot> snapshots);

    MembershipOrderSnapshot putAndGet(MembershipOrderSnapshot snapshot);

    List<MembershipOrderSnapshot> putAndGetAll(List<MembershipOrderSnapshot> snapshots);

    List<MembershipOrderSnapshotWriteResult> writeAll(
            List<MembershipOrderSnapshotWriteCommand> commands);

    Optional<MembershipOrderSnapshot> find(String orderId);

    Optional<MembershipOrderRealtimeGuard> findRealtimeGuard(String orderId);

    Map<String, MembershipOrderSnapshot> findAll(Collection<String> orderIds);

    boolean callbackInProgress(String orderId);

    MembershipProviderTradeNoPatchOutcome patchProviderTradeNo(
            String orderId,
            long loginIdentityId,
            String providerTradeNo);

    MembershipOrderTransitionResult markPaid(
            String orderId,
            String callbackId,
            String providerTradeNo,
            BigDecimal paidAmountYuan,
            OffsetDateTime paidAt);

    Map<String, MembershipOrderTransitionResult> markPaidAll(
            Collection<MembershipOrderPaidCommand> commands);

    MembershipOrderTransitionResult cancel(String orderId, OffsetDateTime cancelledAt);

    /**
     * 为不同幂等键的新订单原子终结旧订单；回调 marker 必须优先于替换，避免已进入处理的支付事实被覆盖。
     */
    MembershipOrderTransitionResult supersedeForReplacement(
            String orderId,
            boolean paymentStartedInDatabase,
            OffsetDateTime changedAt);

    MembershipOrderTransitionResult startClosing(
            String orderId,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime changedAt);

    /**
     * 以原订单截止点计算出的最小关单边界启动 CLOSING；默认实现保持旧调用方兼容，Redis 实现会把边界交给 Lua 复核。
     */
    default MembershipOrderTransitionResult startClosing(
            String orderId,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime changedAt,
            OffsetDateTime minimumClosingDeadlineAt) {
        return startClosing(orderId, closingDeadlineAt, changedAt);
    }

    MembershipOrderTransitionResult finalizeClosing(
            String orderId,
            OffsetDateTime changedAt);

    /** 外部 Provider 关单必须同时提供观测状态与事实来源，由原子脚本复核允许组合及并发前置条件。 */
    MembershipOrderTransitionResult finalizeClosing(
            String orderId,
            PaymentProviderStatus providerStatus,
            MembershipClosingFinalizationSource source,
            OffsetDateTime changedAt);
}
