package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
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

    MembershipOrderTransitionResult startClosing(
            String orderId,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime changedAt);

    MembershipOrderTransitionResult finalizeClosing(
            String orderId,
            OffsetDateTime changedAt);

    MembershipOrderTransitionResult finalizeClosing(
            String orderId,
            PaymentProviderStatus providerStatus,
            OffsetDateTime changedAt);
}
