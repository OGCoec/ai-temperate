package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentCallbackDecision;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentCallbackDecisionService;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundService;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackResumeService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackBatchService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackPersistenceService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackResolutionCommand;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.callback.PaymentProviderResultCompletionAction;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementCommand;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementSettlementService;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentRefundEntitlementCommand;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderPaidCommand;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来恢复和领取回调 ZSet，批量补齐订单、先提交回调审计表，再协调 Redis 状态迁移与 PostgreSQL 权益结算。
 *
 * <p>APPLIED 必须进入独立权益事务，事务提交后才清理 marker；未知订单和字段冲突属于永久拒绝，基础设施异常会精确重入 ready 并结束当前轮。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class PaymentCallbackBatchServiceImpl
        implements PaymentCallbackBatchService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PaymentCallbackBatchServiceImpl.class);
    private static final String SUCCESS = "TRADE_SUCCESS";

    private final PaymentCallbackQueue callbackQueue;
    private final MembershipOrderSnapshotStore orderSnapshotStore;
    private final MembershipOrderMapper orderMapper;
    private final PaymentCallbackPersistenceService persistenceService;
    private final MembershipPaymentEntitlementSettlementService entitlementService;
    private final MembershipPaymentCallbackDecisionService decisionService;
    private final MembershipPaymentRefundService refundService;
    private final MembershipPaymentRejectedCallbackResumeService rejectedResumeService;
    private final MembershipPaymentLoadtestFaultGate loadtestFaultGate;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final ObjectMapper objectMapper;
    private final MembershipPaymentProperties.Callback properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public PaymentCallbackBatchServiceImpl(
            PaymentCallbackQueue callbackQueue,
            MembershipOrderSnapshotStore orderSnapshotStore,
            MembershipOrderMapper orderMapper,
            PaymentCallbackPersistenceService persistenceService,
            MembershipPaymentEntitlementSettlementService entitlementService,
            MembershipPaymentCallbackDecisionService decisionService,
            MembershipPaymentRefundService refundService,
            MembershipPaymentRejectedCallbackResumeService rejectedResumeService,
            MembershipPaymentLoadtestFaultGate loadtestFaultGate,
            HybridBase64UrlCodec base64UrlCodec,
            ObjectMapper objectMapper,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.orderSnapshotStore = Objects.requireNonNull(orderSnapshotStore);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.entitlementService = Objects.requireNonNull(entitlementService);
        this.decisionService = Objects.requireNonNull(decisionService);
        this.refundService = Objects.requireNonNull(refundService);
        this.rejectedResumeService = Objects.requireNonNull(rejectedResumeService);
        this.loadtestFaultGate = Objects.requireNonNull(loadtestFaultGate);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties).callback();
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void flushOneRun() {
        long now = clock.millis();
        int recovered = callbackQueue.recoverTimedOut(
                now - properties.processingTimeout().toMillis(),
                properties.batchSize(),
                now);
        metrics.callbackRecovered(recovered);
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            List<PaymentCallbackClaim> claims = callbackQueue.claim(
                    properties.batchSize(), clock.millis());
            if (claims.isEmpty()) {
                updateProcessingGauge();
                return;
            }
            if (!process(claims)) {
                updateProcessingGauge();
                return;
            }
        }
        updateProcessingGauge();
    }

    private boolean process(List<PaymentCallbackClaim> claims) {
        List<PaymentCallbackClaim> retryOnFailure = claims;
        try {
            Map<String, PaymentCallbackClaim> claimsById = claims.stream()
                    .collect(Collectors.toMap(
                            PaymentCallbackClaim::callbackId,
                            claim -> claim,
                            (left, right) -> left,
                            LinkedHashMap::new));
            Map<String, PaymentCallbackSnapshot> callbacks = new LinkedHashMap<>(
                    callbackQueue.findAll(claimsById.keySet()));
            List<PaymentCallbackClaim> heldClaims = callbacks.values().stream()
                    .filter(callback -> loadtestFaultGate.callbackHeld(callback.orderId()))
                    .map(callback -> claimsById.get(callback.callbackId()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!heldClaims.isEmpty()) {
                // hold 只把目标订单重入 ready；同批其他回调继续处理，避免本机竞态探针阻塞无关订单。
                callbackQueue.requeue(heldClaims, clock.millis() + 1_000L);
                Set<String> heldIds = heldClaims.stream()
                        .map(PaymentCallbackClaim::callbackId)
                        .collect(Collectors.toSet());
                heldIds.forEach(callbacks::remove);
                heldIds.forEach(claimsById::remove);
            }
            List<PaymentCallbackClaim> activeClaims = claims.stream()
                    .filter(claim -> claimsById.containsKey(claim.callbackId()))
                    .toList();
            retryOnFailure = activeClaims;
            if (activeClaims.isEmpty()) {
                return true;
            }
            List<PaymentCallbackCompletion> complete = activeClaims.stream()
                    .filter(claim -> !callbacks.containsKey(claim.callbackId()))
                    .map(claim -> new PaymentCallbackCompletion(claim, null))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!complete.isEmpty()) {
                metrics.callbackRejected(complete.size());
                LOGGER.warn(
                        "Membership payment callback data was missing; traceId={} count={}",
                        MembershipPaymentTraceContext.currentTraceId(),
                        complete.size());
            }

            ResolvedOrders resolvedOrders = resolveOrders(
                    callbacks.values().stream()
                            .map(PaymentCallbackSnapshot::orderId)
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
            Map<String, MembershipOrderSnapshot> orders = resolvedOrders.snapshots();
            List<PaymentCallbackSnapshot> validCallbacks = new ArrayList<>();
            int permanentlyRejected = 0;
            for (PaymentCallbackSnapshot callback : callbacks.values()) {
                MembershipOrderSnapshot order = orders.get(callback.orderId());
                if (!validForPersistence(callback, order)) {
                    complete.add(completion(
                            claimsById,
                            callback,
                            PaymentProviderResultCompletionAction.REMOVE));
                    permanentlyRejected++;
                    continue;
                }
                validCallbacks.add(callback);
            }
            if (permanentlyRejected > 0) {
                metrics.callbackRejected(permanentlyRejected);
                LOGGER.warn(
                        "Membership payment callbacks were permanently rejected; "
                                + "traceId={} count={}",
                        MembershipPaymentTraceContext.currentTraceId(),
                        permanentlyRejected);
            }

            if (!validCallbacks.isEmpty()) {
                List<MembershipPaymentCallbackWriteResult> writes =
                        persistenceService.persist(validCallbacks);
                List<MembershipOrderPaidCommand> paidCommands = new ArrayList<>();
                Map<String, CallbackWork> paidWork = new LinkedHashMap<>();
                List<PaymentCallbackResolutionCommand> resolutions = new ArrayList<>();
                List<MembershipPaymentEntitlementCommand> entitlementCommands =
                        new ArrayList<>();
                List<MembershipPaymentRefundEntitlementCommand> refundEntitlements =
                        new ArrayList<>();
                List<PaymentRefundCommand> refundCommands = new ArrayList<>();
                List<MembershipOrderSnapshot> unappliedOrders = new ArrayList<>();
                OffsetDateTime resolvedAt = MembershipPaymentTime.now(clock);
                for (MembershipPaymentCallbackWriteResult write : writes) {
                    int ordinal = requiredOrdinal(write, validCallbacks.size());
                    PaymentCallbackSnapshot callback = validCallbacks.get(ordinal - 1);
                    if (Boolean.TRUE.equals(write.getOrderMismatch())) {
                        complete.add(completion(
                                claimsById,
                                callback,
                                PaymentProviderResultCompletionAction.REMOVE));
                        metrics.callbackRejected();
                        LOGGER.warn(
                                "Membership payment provider trade was bound to another order; "
                                        + "traceId={}",
                                MembershipPaymentTraceContext.currentTraceId());
                        continue;
                    }
                    if (!Boolean.TRUE.equals(write.getInserted())
                            && !Boolean.TRUE.equals(write.getDuplicate())) {
                        throw new IllegalStateException(
                                "Payment callback persistence outcome is invalid.");
                    }
                    MembershipOrderSnapshot resolvedOrder =
                            orders.get(callback.orderId());
                    if (!Boolean.TRUE.equals(write.getInserted())
                            && !Boolean.TRUE.equals(write.getSameCallback())) {
                        // 新通知命中订单或第三方流水唯一事实时必须彻底 no-op，禁止修改原 resolution、订单或退款指标。
                        complete.add(completion(
                                claimsById,
                                callback,
                                PaymentProviderResultCompletionAction.REMOVE));
                        continue;
                    }
                    if (write.getResolution() != null) {
                        if (MembershipPaymentCallbackResolution.REFUND_REQUIRED.name()
                                .equals(write.getResolution())) {
                            // resolution 已提交但 HTTP 或 Redis complete 中断时，恢复任务必须再次执行同一幂等退款。
                            refundCommands.add(refundCommand(callback));
                            unappliedOrders.add(resolvedOrder);
                            if (write.getOrderEntitlementResolution() == null) {
                                refundEntitlements.add(refundEntitlementCommand(
                                        write, callback, resolvedAt));
                            }
                        } else if (MembershipPaymentCallbackResolution.APPLIED.name()
                                .equals(write.getResolution())
                                && write.getOrderEntitlementResolution() == null
                                && resolvedOrder.status() == MembershipOrderStatus.PAID) {
                            // 兼容数据库回调已解析但旧进程尚未原子发放权益的恢复窗口；已发放订单由投影字段直接跳过。
                            entitlementCommands.add(entitlementCommand(
                                    write, resolvedOrder, resolvedAt));
                        } else if (MembershipPaymentCallbackResolution.REJECTED.name()
                                .equals(write.getResolution())) {
                            // resolution 已提交但 Marker 清理曾中断时，恢复任务必须再次发布最终阶段，重复消息由状态机幂等吸收。
                            unappliedOrders.add(resolvedOrder);
                        }
                        complete.add(completion(
                                claimsById,
                                callback,
                                providerResultCompletionAction(
                                        MembershipPaymentCallbackResolution.valueOf(
                                                write.getResolution()))));
                        continue;
                    }
                    if (unresolvedAppliedRecovery(write, resolvedOrder, callback)) {
                        // 订单已被同一 callback 推进为 PAID、但权益事务尚未提交时，恢复必须重新进入原子发放，不能只补写 callback APPLIED。
                        entitlementCommands.add(entitlementCommand(
                                write, resolvedOrder, resolvedAt));
                        complete.add(completion(claimsById, callback));
                        continue;
                    }
                    MembershipPaymentCallbackDecision decision =
                            decisionService.decide(resolvedOrder, callback);
                    if (!decision.applyPayment()) {
                        MembershipPaymentCallbackResolution resolution =
                                duplicateCrashResolution(write, decision.resolution());
                        if (decision.refundRequired()) {
                            refundEntitlements.add(refundEntitlementCommand(
                                    write, callback, resolvedAt));
                            refundCommands.add(refundCommand(callback));
                            unappliedOrders.add(resolvedOrder);
                        } else {
                            resolutions.add(resolutionCommand(
                                    write, resolution, resolvedAt));
                            if (resolution == MembershipPaymentCallbackResolution.REJECTED) {
                                unappliedOrders.add(resolvedOrder);
                            }
                        }
                        complete.add(completion(
                                claimsById,
                                callback,
                                providerResultCompletionAction(decision.resolution())));
                        continue;
                    }
                    MembershipOrderPaidCommand paidCommand = new MembershipOrderPaidCommand(
                            callback.callbackId(),
                            callback.orderId(),
                            callback.providerTradeNo(),
                            callback.paidAmountYuan(),
                            callback.paidAt(),
                            resolvedAt);
                    paidCommands.add(paidCommand);
                    paidWork.put(callback.callbackId(), new CallbackWork(
                            write, callback, resolvedOrder));
                }
                boolean fullyProcessed = completePaidCallbacks(
                        paidCommands,
                        paidWork,
                        claimsById,
                        complete,
                        resolutions,
                        entitlementCommands,
                        refundEntitlements,
                        refundCommands,
                        unappliedOrders,
                        resolvedAt);
                // 空批次不经过事务代理；恢复任务已经完成权益裁决时只清理 Redis，不开启无意义数据库事务。
                if (!entitlementCommands.isEmpty()) {
                    entitlementService.settleApplied(entitlementCommands);
                }
                if (!refundEntitlements.isEmpty()) {
                    entitlementService.settleRefundRequired(refundEntitlements);
                }
                persistenceService.resolve(resolutions);
                resumeUnappliedOrders(unappliedOrders);
                refundCommands.stream().distinct().forEach(refundService::refund);
                completeCallbacks(complete);
                return fullyProcessed;
            }
            completeCallbacks(complete);
            return true;
        } catch (RuntimeException exception) {
            safeRequeue(retryOnFailure);
            LOGGER.warn(
                    "Membership payment callback batch will retry; "
                            + "traceId={} count={} reason={}",
                    MembershipPaymentTraceContext.currentTraceId(),
                    retryOnFailure.size(),
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private boolean completePaidCallbacks(
            List<MembershipOrderPaidCommand> commands,
            Map<String, CallbackWork> paidWork,
            Map<String, PaymentCallbackClaim> claimsById,
            List<PaymentCallbackCompletion> complete,
            List<PaymentCallbackResolutionCommand> resolutions,
            List<MembershipPaymentEntitlementCommand> entitlementCommands,
            List<MembershipPaymentRefundEntitlementCommand> refundEntitlements,
            List<PaymentRefundCommand> refundCommands,
            List<MembershipOrderSnapshot> unappliedOrders,
            OffsetDateTime resolvedAt) {
        if (commands.isEmpty()) {
            return true;
        }
        Map<String, MembershipOrderTransitionResult> transitions =
                orderSnapshotStore.markPaidAll(commands);
        List<PaymentCallbackClaim> retry = new ArrayList<>();
        for (MembershipOrderPaidCommand command : commands) {
            CallbackWork work = paidWork.get(command.callbackId());
            if (work == null) {
                throw new IllegalStateException("Payment callback work item is missing.");
            }
            MembershipOrderTransitionResult transition = transitions.get(command.callbackId());
            if (transition == null) {
                throw new IllegalStateException("Payment callback transition result is missing.");
            }
            if (transition.outcome() == MembershipOrderTransitionOutcome.MISSING) {
                retry.add(claimsById.get(command.callbackId()));
                continue;
            }
            MembershipPaymentCallbackResolution resolution =
                    transitionResolution(transition, work.write());
            if (resolution == MembershipPaymentCallbackResolution.APPLIED) {
                MembershipOrderSnapshot paidOrder = paidOrder(
                        work.order(),
                        work.callback(),
                        transition.stateVersion(),
                        resolvedAt);
                entitlementCommands.add(entitlementCommand(
                        work.write(), paidOrder, resolvedAt));
            } else if (resolution == MembershipPaymentCallbackResolution.REFUND_REQUIRED) {
                refundEntitlements.add(refundEntitlementCommand(
                        work.write(), work.callback(), resolvedAt));
                refundCommands.add(refundCommand(work.callback()));
                unappliedOrders.add(work.order());
                // 告警只记录终态，不包含订单、回调或平台流水，避免高基数与敏感标识进入日志。
                LOGGER.warn(
                        "Membership payment callback requires refund; traceId={} status={}",
                        MembershipPaymentTraceContext.currentTraceId(),
                        requireStatus(transition.status(), work.order().status()));
                metrics.latePaid();
            } else if (resolution == MembershipPaymentCallbackResolution.REJECTED) {
                resolutions.add(resolutionCommand(
                        work.write(), resolution, resolvedAt));
                unappliedOrders.add(work.order());
                metrics.callbackRejected();
                LOGGER.warn(
                        "Membership payment callback state transition was rejected; "
                                + "traceId={} outcome={}",
                        MembershipPaymentTraceContext.currentTraceId(),
                        transition.outcome());
            } else {
                resolutions.add(resolutionCommand(
                        work.write(), resolution, resolvedAt));
            }
            complete.add(new PaymentCallbackCompletion(
                    claimsById.get(command.callbackId()),
                    command.orderId(),
                    providerResultCompletionAction(resolution)));
        }
        if (!retry.isEmpty()) {
            callbackQueue.requeue(distinctClaims(retry), clock.millis());
        }
        return retry.isEmpty();
    }

    /**
     * REJECTED 与 REFUND_REQUIRED 都可能保持原活动状态；必须先可靠发布恢复消息，再删除 Marker，
     * 避免旧 MQ 链因回调并发短路后永久停留在 PENDING_PAYMENT 或 CLOSING。
     */
    private void resumeUnappliedOrders(Collection<MembershipOrderSnapshot> orders) {
        orders.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        MembershipOrderSnapshot::orderId,
                        order -> order,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .forEach(rejectedResumeService::resume);
    }

    private static PaymentRefundCommand refundCommand(PaymentCallbackSnapshot callback) {
        return new PaymentRefundCommand(
                callback.orderId(),
                callback.providerTradeNo(),
                callback.paidAmountYuan());
    }

    private MembershipPaymentEntitlementCommand entitlementCommand(
            MembershipPaymentCallbackWriteResult write,
            MembershipOrderSnapshot paidOrder,
            OffsetDateTime resolvedAt) {
        return new MembershipPaymentEntitlementCommand(
                persistedCallbackId(write),
                paidOrder,
                resolvedAt);
    }

    private MembershipPaymentRefundEntitlementCommand refundEntitlementCommand(
            MembershipPaymentCallbackWriteResult write,
            PaymentCallbackSnapshot callback,
            OffsetDateTime resolvedAt) {
        return new MembershipPaymentRefundEntitlementCommand(
                persistedCallbackId(write),
                callback.orderId(),
                resolvedAt);
    }

    private String persistedCallbackId(MembershipPaymentCallbackWriteResult write) {
        byte[] persisted = write.getPersistedCallbackId();
        if (persisted == null) {
            throw new IllegalStateException("Payment callback persisted ID is missing.");
        }
        return base64UrlCodec.encode(persisted);
    }

    private static MembershipOrderSnapshot paidOrder(
            MembershipOrderSnapshot current,
            PaymentCallbackSnapshot callback,
            long stateVersion,
            OffsetDateTime changedAt) {
        return new MembershipOrderSnapshot(
                current.schemaVersion(),
                current.orderId(),
                current.loginIdentityId(),
                current.membershipTier(),
                current.payAmountYuan(),
                current.payType(),
                MembershipOrderStatus.PAID,
                current.idempotencyKey(),
                callback.providerTradeNo(),
                current.paymentStartedAt(),
                current.expiresAt(),
                current.closingDeadlineAt(),
                callback.paidAt(),
                stateVersion,
                current.createdAt(),
                changedAt);
    }

    private ResolvedOrders resolveOrders(Set<String> orderIds) {
        if (orderIds.isEmpty()) {
            return new ResolvedOrders(Map.of());
        }
        Map<String, MembershipOrderSnapshot> resolved = new LinkedHashMap<>(
                orderSnapshotStore.findAll(orderIds));
        List<String> missing = orderIds.stream()
                .filter(orderId -> !resolved.containsKey(orderId))
                .toList();
        if (missing.isEmpty()) {
            return new ResolvedOrders(Map.copyOf(resolved));
        }
        List<MembershipOrder> persisted = orderMapper.findByIdsJson(idsJson(missing));
        List<MembershipOrderSnapshot> rebuild = new ArrayList<>();
        for (MembershipOrder order : persisted) {
            MembershipOrderSnapshot snapshot = toSnapshot(order);
            resolved.put(snapshot.orderId(), snapshot);
            if (!snapshot.status().terminal()) {
                rebuild.add(snapshot);
            }
        }
        orderSnapshotStore.putAll(rebuild);
        return new ResolvedOrders(Map.copyOf(resolved));
    }

    private boolean validForPersistence(
            PaymentCallbackSnapshot callback,
            MembershipOrderSnapshot order) {
        // 已通过同步格式校验的成功通知必须先形成唯一审计事实；金额、支付方式和时间边界由后续状态机写入 REJECTED/APPLIED。
        return order != null
                && SUCCESS.equals(callback.tradeStatus());
    }

    private String idsJson(Collection<String> orderIds) {
        List<String> hex = orderIds.stream()
                .map(base64UrlCodec::decode)
                .map(HexFormat.of()::formatHex)
                .toList();
        try {
            return objectMapper.writeValueAsString(hex);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Membership order lookup serialization failed.", exception);
        }
    }

    private MembershipOrderSnapshot toSnapshot(MembershipOrder order) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(order.getId()),
                order.getLoginIdentityId(),
                order.getMembershipTier(),
                order.getPayAmountYuan(),
                order.getPayType(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getProviderTradeNo(),
                order.getPaymentStartedAt(),
                order.getExpiresAt(),
                order.getClosingDeadlineAt(),
                order.getPaidAt(),
                order.getStateVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private static int requiredOrdinal(
            MembershipPaymentCallbackWriteResult write,
            int size) {
        if (write == null
                || write.getOrdinal() == null
                || write.getOrdinal() < 1
                || write.getOrdinal() > size) {
            throw new IllegalStateException("Payment callback persistence ordinal is invalid.");
        }
        return write.getOrdinal();
    }

    private PaymentCallbackResolutionCommand resolutionCommand(
            MembershipPaymentCallbackWriteResult write,
            MembershipPaymentCallbackResolution resolution,
            OffsetDateTime resolvedAt) {
        byte[] persistedCallbackId = write.getPersistedCallbackId();
        if (persistedCallbackId == null) {
            throw new IllegalStateException(
                    "Payment callback persisted ID is missing.");
        }
        return new PaymentCallbackResolutionCommand(
                base64UrlCodec.encode(persistedCallbackId),
                resolution,
                resolvedAt);
    }

    private static boolean unresolvedAppliedRecovery(
            MembershipPaymentCallbackWriteResult write,
            MembershipOrderSnapshot order,
            PaymentCallbackSnapshot callback) {
        return !Boolean.TRUE.equals(write.getInserted())
                && Boolean.TRUE.equals(write.getDuplicate())
                && Boolean.TRUE.equals(write.getSameCallback())
                && order.status() == MembershipOrderStatus.PAID
                && Objects.equals(order.providerTradeNo(), callback.providerTradeNo());
    }

    /** 只有相同 callback ID 的恢复任务才会进入此分支；新通知已在前面按唯一事实 no-op。 */
    private static MembershipPaymentCallbackResolution duplicateCrashResolution(
            MembershipPaymentCallbackWriteResult write,
            MembershipPaymentCallbackResolution resolution) {
        if (Boolean.TRUE.equals(write.getDuplicate())
                && resolution == MembershipPaymentCallbackResolution.ALREADY_APPLIED) {
            return MembershipPaymentCallbackResolution.APPLIED;
        }
        return resolution;
    }

    private static MembershipPaymentCallbackResolution transitionResolution(
            MembershipOrderTransitionResult transition,
            MembershipPaymentCallbackWriteResult write) {
        return switch (transition.outcome()) {
            case APPLIED -> MembershipPaymentCallbackResolution.APPLIED;
            case ALREADY_APPLIED -> Boolean.TRUE.equals(write.getDuplicate())
                    ? MembershipPaymentCallbackResolution.APPLIED
                    : MembershipPaymentCallbackResolution.ALREADY_APPLIED;
            case LATE_TERMINAL, PROVIDER_TRADE_CONFLICT ->
                    MembershipPaymentCallbackResolution.REFUND_REQUIRED;
            case NOT_ALLOWED,
                    TOO_EARLY,
                    CALLBACK_IN_PROGRESS,
                    PROVIDER_STATUS_UNSAFE,
                    AMOUNT_MISMATCH -> MembershipPaymentCallbackResolution.REJECTED;
            case MISSING -> throw new IllegalStateException(
                    "Missing transition must be requeued before resolution.");
        };
    }

    private static MembershipOrderStatus requireStatus(
            MembershipOrderStatus transitionStatus,
            MembershipOrderStatus fallback) {
        return transitionStatus == null ? Objects.requireNonNull(fallback) : transitionStatus;
    }

    /**
     * REJECTED 与 REFUND_REQUIRED 都不能让模拟 Provider 继续暴露 PAID；否则恢复的关单消息会
     * 永久把活动订单判为已支付。Marker 与 Provider Result 必须由同一 complete Lua 原子收口。
     */
    private static PaymentProviderResultCompletionAction providerResultCompletionAction(
            MembershipPaymentCallbackResolution resolution) {
        return resolution == MembershipPaymentCallbackResolution.REJECTED
                        || resolution == MembershipPaymentCallbackResolution.REFUND_REQUIRED
                ? PaymentProviderResultCompletionAction.RESET_UNPAID
                : PaymentProviderResultCompletionAction.KEEP;
    }

    private void safeRequeue(List<PaymentCallbackClaim> claims) {
        try {
            callbackQueue.requeue(distinctClaims(claims), clock.millis());
        } catch (RuntimeException requeueFailure) {
            LOGGER.error(
                    "Membership payment callback requeue failed; "
                            + "traceId={} count={} reason={}",
                    MembershipPaymentTraceContext.currentTraceId(),
                    claims.size(),
                    requeueFailure.getClass().getSimpleName());
        }
    }

    private static List<PaymentCallbackClaim> distinctClaims(
            Collection<PaymentCallbackClaim> claims) {
        return claims.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static List<PaymentCallbackCompletion> distinctCompletions(
            Collection<PaymentCallbackCompletion> completions) {
        return completions.stream().filter(Objects::nonNull).distinct().toList();
    }

    private void completeCallbacks(
            Collection<PaymentCallbackCompletion> completions) {
        List<PaymentCallbackCompletion> distinct = distinctCompletions(completions);
        // 故障点位于数据库 resolution 与订单迁移完成之后、Redis complete 之前，精确模拟进程在提交后中断。
        loadtestFaultGate.failBeforeCallbackCompleteIfArmed(distinct);
        callbackQueue.complete(distinct);
    }

    private static PaymentCallbackCompletion completion(
            Map<String, PaymentCallbackClaim> claimsById,
            PaymentCallbackSnapshot callback) {
        return completion(
                claimsById,
                callback,
                PaymentProviderResultCompletionAction.KEEP);
    }

    private static PaymentCallbackCompletion completion(
            Map<String, PaymentCallbackClaim> claimsById,
            PaymentCallbackSnapshot callback,
            PaymentProviderResultCompletionAction providerResultAction) {
        return new PaymentCallbackCompletion(
                claimsById.get(callback.callbackId()),
                callback.orderId(),
                providerResultAction);
    }

    /** 该解析结果承载 Redis 实时命中与 PostgreSQL 批量兜底后的完整订单集合。 */
    private record ResolvedOrders(Map<String, MembershipOrderSnapshot> snapshots) {
    }

    /** 该工作项把输入回调、数据库裁决和订单状态绑定，供 Lua 结果写回正确 callback resolution。 */
    private record CallbackWork(
            MembershipPaymentCallbackWriteResult write,
            PaymentCallbackSnapshot callback,
            MembershipOrderSnapshot order) {
    }

    private void updateProcessingGauge() {
        try {
            metrics.callbackProcessingSize(callbackQueue.processingSize());
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "Membership payment callback processing gauge is unavailable; traceId={}",
                    MembershipPaymentTraceContext.currentTraceId());
        }
    }
}
