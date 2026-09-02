package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentCallbackDecision;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentCallbackDecisionService;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackResumeService;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundRequiredFinalizationCommand;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackReleaseCommand;
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
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentWorker;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.refund.MembershipRefundAttemptCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentUnappliedCallbackStore;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentMissingSnapshotReleaseOutcome;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerOutcome;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerRunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final MembershipPaymentUnappliedCallbackStore unappliedCallbackStore;
    private final MembershipOrderMapper orderMapper;
    private final PaymentCallbackPersistenceService persistenceService;
    private final MembershipPaymentEntitlementSettlementService entitlementService;
    private final MembershipPaymentCallbackDecisionService decisionService;
    private final MembershipRefundAttemptCoordinator refundCoordinator;
    private final MembershipPaymentRejectedCallbackResumeService rejectedResumeService;
    private final MembershipPaymentLoadtestFaultGate loadtestFaultGate;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final ObjectMapper objectMapper;
    private final MembershipPaymentProperties.Callback properties;
    private final Duration closingDuration;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public PaymentCallbackBatchServiceImpl(
            PaymentCallbackQueue callbackQueue,
            MembershipOrderSnapshotStore orderSnapshotStore,
            MembershipPaymentUnappliedCallbackStore unappliedCallbackStore,
            MembershipOrderMapper orderMapper,
            PaymentCallbackPersistenceService persistenceService,
            MembershipPaymentEntitlementSettlementService entitlementService,
            MembershipPaymentCallbackDecisionService decisionService,
            MembershipRefundAttemptCoordinator refundCoordinator,
            MembershipPaymentRejectedCallbackResumeService rejectedResumeService,
            MembershipPaymentLoadtestFaultGate loadtestFaultGate,
            HybridBase64UrlCodec base64UrlCodec,
            ObjectMapper objectMapper,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.orderSnapshotStore = Objects.requireNonNull(orderSnapshotStore);
        this.unappliedCallbackStore = Objects.requireNonNull(unappliedCallbackStore);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.entitlementService = Objects.requireNonNull(entitlementService);
        this.decisionService = Objects.requireNonNull(decisionService);
        this.refundCoordinator = Objects.requireNonNull(refundCoordinator);
        this.rejectedResumeService = Objects.requireNonNull(rejectedResumeService);
        this.loadtestFaultGate = Objects.requireNonNull(loadtestFaultGate);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        MembershipPaymentProperties validProperties = Objects.requireNonNull(properties);
        this.properties = validProperties.callback();
        this.closingDuration = validProperties.closingDuration();
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public MembershipPaymentWorkerRunResult flushOneRun() {
        long startedNanos = System.nanoTime();
        int batches = 0;
        int claimedItems = 0;
        MembershipPaymentWorkerOutcome outcome = MembershipPaymentWorkerOutcome.CAPACITY;
        try {
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
                    outcome = MembershipPaymentWorkerOutcome.DRAINED;
                    updateProcessingGauge();
                    return new MembershipPaymentWorkerRunResult(
                            batches, claimedItems, outcome);
                }
                batches++;
                claimedItems += claims.size();
                if (!process(claims)) {
                    outcome = MembershipPaymentWorkerOutcome.RETRY;
                    updateProcessingGauge();
                    return new MembershipPaymentWorkerRunResult(
                            batches, claimedItems, outcome);
                }
            }
            updateProcessingGauge();
            return new MembershipPaymentWorkerRunResult(batches, claimedItems, outcome);
        } catch (RuntimeException exception) {
            outcome = MembershipPaymentWorkerOutcome.FAILED;
            throw exception;
        } finally {
            recordWorkerRun(batches, claimedItems, outcome, startedNanos);
        }
    }

    private void recordWorkerRun(
            int batches,
            int claimedItems,
            MembershipPaymentWorkerOutcome outcome,
            long startedNanos) {
        try {
            metrics.workerRunCompleted(
                    MembershipPaymentWorker.CALLBACK,
                    batches,
                    claimedItems,
                    outcome.name().toLowerCase(java.util.Locale.ROOT),
                    System.nanoTime() - startedNanos,
                    Thread.currentThread().getName());
        } catch (RuntimeException exception) {
            // 压测观测不得改变回调收敛语义；指标异常只降低证据完整性，由外部采样门禁终止正式测试。
            LOGGER.debug(
                    "Membership payment callback worker observation failed; traceId={}",
                    MembershipPaymentTraceContext.currentTraceId());
        }
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
                List<RefundAttemptWork> refundCommands = new ArrayList<>();
                List<MembershipPaymentRefundRequiredFinalizationCommand>
                        refundFinalizations = new ArrayList<>();
                List<RefundRecoveryWork> refundRecoveries = new ArrayList<>();
                List<MembershipPaymentRejectedCallbackReleaseCommand> rejectedReleases =
                        new ArrayList<>();
                List<MembershipOrderSnapshot> rejectedOrders = new ArrayList<>();
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
                            // 历史版本可能已经写入 REFUND_REQUIRED 却仍在订单表绑定第三方流水；
                            // 同源回调恢复时必须重放幂等终态事务，先修复数据库事实，再执行 Redis 收敛和外部退款。
                            refundCommands.add(refundAttemptWork(claimsById, callback));
                            refundFinalizations.add(refundFinalizationCommand(
                                    claimsById, callback, resolvedOrder, resolvedAt));
                            refundEntitlements.add(refundEntitlementCommand(
                                    write, callback, resolvedAt));
                            refundRecoveries.add(new RefundRecoveryWork(
                                    refundProvider(callback.providerTradeNo()),
                                    entitlementClass(write.getOrderEntitlementResolution()),
                                    resolvedOrder != null
                                            && resolvedOrder.providerTradeNo() != null));
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
                            rejectedReleases.add(rejectedReleaseCommand(
                                    claimsById, callback));
                            rejectedOrders.add(resolvedOrder);
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
                            refundCommands.add(refundAttemptWork(claimsById, callback));
                            refundFinalizations.add(refundFinalizationCommand(
                                    claimsById, callback, resolvedOrder, resolvedAt));
                        } else {
                            resolutions.add(resolutionCommand(
                                    write, resolution, resolvedAt));
                            if (resolution == MembershipPaymentCallbackResolution.REJECTED) {
                                rejectedReleases.add(rejectedReleaseCommand(
                                        claimsById, callback));
                                rejectedOrders.add(resolvedOrder);
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
                        refundFinalizations,
                        rejectedReleases,
                        rejectedOrders,
                        resolvedAt);
                // 空批次不经过事务代理；恢复任务已经完成权益裁决时只清理 Redis，不开启无意义数据库事务。
                if (!entitlementCommands.isEmpty()) {
                    entitlementService.settleApplied(entitlementCommands);
                }
                if (!refundEntitlements.isEmpty()) {
                    logRefundRecovery(
                            refundRecoveries,
                            "started",
                            "LEGACY_TERMINAL_REPAIR_REQUIRED");
                    try {
                        entitlementService.settleRefundRequired(refundEntitlements);
                    } catch (RuntimeException exception) {
                        logRefundRecovery(
                                refundRecoveries,
                                "failed",
                                "TERMINAL_SETTLEMENT_FAILED");
                        throw new SafeCallbackFailure(
                                "refund_terminal_settlement",
                                "TERMINAL_SETTLEMENT_FAILED",
                                exception);
                    }
                    logRefundRecovery(
                            refundRecoveries,
                            "completed",
                            "TERMINAL_SETTLEMENT_REAPPLIED");
                }
                persistenceService.resolve(resolutions);
                finalizeRefundRequired(refundFinalizations);
                releaseRejected(rejectedReleases);
                resumeRejectedOrders(rejectedOrders);
                try {
                    refundCommands.stream().distinct().forEach(work ->
                            refundCoordinator.processInitial(
                                    work.callbackId(), work.command()));
                } catch (RuntimeException exception) {
                    throw new SafeCallbackFailure(
                            "refund_coordination",
                            "REFUND_COORDINATION_FAILED",
                            exception);
                }
                completeCallbacks(complete);
                return fullyProcessed;
            }
            completeCallbacks(complete);
            return true;
        } catch (RuntimeException exception) {
            safeRequeue(retryOnFailure);
            CallbackFailureClassification failure = classifyFailure(exception);
            MembershipPaymentLifecycleDiagnostics.callbackRetry(
                    failure.stage(),
                    failure.reason(),
                    failure.exceptionClass(),
                    retryOnFailure.size(),
                    properties.flushIntervalMillis(),
                    MembershipPaymentTraceContext.currentTraceId());
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
            List<RefundAttemptWork> refundCommands,
            List<MembershipPaymentRefundRequiredFinalizationCommand> refundFinalizations,
            List<MembershipPaymentRejectedCallbackReleaseCommand> rejectedReleases,
            List<MembershipOrderSnapshot> rejectedOrders,
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
                refundCommands.add(refundAttemptWork(claimsById, work.callback()));
                refundFinalizations.add(refundFinalizationCommand(
                        claimsById, work.callback(), work.order(), resolvedAt));
                // 告警只记录终态，不包含订单、回调或平台流水，避免高基数与敏感标识进入日志。
                LOGGER.warn(
                        "Membership payment callback requires refund; traceId={} status={}",
                        MembershipPaymentTraceContext.currentTraceId(),
                        requireStatus(transition.status(), work.order().status()));
                metrics.latePaid();
            } else if (resolution == MembershipPaymentCallbackResolution.REJECTED) {
                resolutions.add(resolutionCommand(
                        work.write(), resolution, resolvedAt));
                rejectedReleases.add(rejectedReleaseCommand(
                        claimsById, work.callback()));
                rejectedOrders.add(work.order());
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
     * 退款权益事务已经提交后，本地订单可以立即进入 CLOSED；外部退款重试只由独立协调状态和延迟队列授权。
     */
    private void finalizeRefundRequired(
            Collection<MembershipPaymentRefundRequiredFinalizationCommand> commands) {
        List<MembershipPaymentRefundRequiredFinalizationCommand> distinct = commands.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        command -> command.claim().callbackId(),
                        command -> command,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        if (distinct.isEmpty()) {
            return;
        }
        Map<String, MembershipOrderTransitionResult> results;
        try {
            results = unappliedCallbackStore.finalizeRefundRequired(distinct);
        } catch (RuntimeException exception) {
            throw new SafeCallbackFailure(
                    "refund_preflight",
                    "REFUND_FINALIZATION_FAILED",
                    exception);
        }
        if (results == null) {
            throw new SafeCallbackFailure(
                    "refund_preflight",
                    "REFUND_FINALIZATION_INCOMPLETE",
                    new IllegalStateException());
        }
        List<MembershipPaymentRefundRequiredFinalizationCommand> missing =
                new ArrayList<>();
        boolean complete = distinct.stream().allMatch(command -> {
            MembershipOrderTransitionResult result =
                    results.get(command.claim().callbackId());
            if (result == null) {
                return false;
            }
            if (result.outcome() == MembershipOrderTransitionOutcome.MISSING) {
                missing.add(command);
                return true;
            }
            return (result.outcome() == MembershipOrderTransitionOutcome.APPLIED
                            || result.outcome()
                                    == MembershipOrderTransitionOutcome.ALREADY_APPLIED)
                    && terminalRefundStatus(result.status());
        });
        if (!complete || results.size() != distinct.size()) {
            throw new SafeCallbackFailure(
                    "refund_preflight",
                    "REFUND_FINALIZATION_INCOMPLETE",
                    new IllegalStateException());
        }
        releaseMissingRefundRequired(missing);
    }

    /**
     * 快照缺失本身不是成功证据；只有数据库回调、订单和第三方流水全部匹配终态后才能释放 Redis 临时事实。
     */
    private void releaseMissingRefundRequired(
            List<MembershipPaymentRefundRequiredFinalizationCommand> missing) {
        if (missing.isEmpty()) {
            return;
        }
        List<String> callbackIds = missing.stream()
                .map(command -> command.claim().callbackId())
                .toList();
        Map<String, MembershipPaymentRefundTerminalFact> facts =
                persistenceService.findRefundTerminalFacts(callbackIds);
        boolean authoritative = facts != null && facts.size() == missing.size();
        String failureReason = authoritative ? null : "CALLBACK_FACT_MISSING";
        for (MembershipPaymentRefundRequiredFinalizationCommand command : missing) {
            MembershipPaymentRefundTerminalFact fact = facts == null
                    ? null
                    : facts.get(command.claim().callbackId());
            RefundTerminalFactCheck check = exactRefundTerminalFact(command, fact);
            authoritative &= check.verified();
            if (!check.verified() && failureReason == null) {
                failureReason = check.reason();
            }
        }
        if (!authoritative) {
            throw new SafeCallbackFailure(
                    "refund_preflight",
                    Objects.requireNonNullElse(failureReason, "CALLBACK_FACT_MISSING"),
                    new IllegalStateException());
        }
        Map<String, MembershipPaymentMissingSnapshotReleaseOutcome> releases;
        try {
            releases = unappliedCallbackStore.releaseMissingRefundRequired(missing);
        } catch (RuntimeException exception) {
            throw new SafeCallbackFailure(
                    "refund_preflight",
                    "MISSING_SNAPSHOT_RELEASE_FAILED",
                    exception);
        }
        boolean releaseSetMatches = releases != null && releases.size() == missing.size();
        boolean released = releaseSetMatches;
        for (MembershipPaymentRefundRequiredFinalizationCommand command : missing) {
            MembershipPaymentMissingSnapshotReleaseOutcome outcome = releases == null
                    ? null
                    : releases.get(command.claim().callbackId());
            boolean commandReleased = releaseSetMatches
                    && (outcome == MembershipPaymentMissingSnapshotReleaseOutcome.RELEASED
                            || outcome
                            == MembershipPaymentMissingSnapshotReleaseOutcome.ALREADY_RELEASED);
            MembershipPaymentLifecycleDiagnostics.refundPreflight(
                    refundProvider(command.providerTradeNo()),
                    "missing_snapshot_release",
                    "missing",
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    false,
                    commandReleased ? "verified" : "failed",
                    commandReleased ? "VERIFIED" : "MISSING_SNAPSHOT_RELEASE_FAILED",
                    MembershipPaymentTraceContext.currentTraceId());
            released &= commandReleased;
        }
        if (!released) {
            throw new SafeCallbackFailure(
                    "refund_preflight",
                    "MISSING_SNAPSHOT_RELEASE_FAILED",
                    new IllegalStateException());
        }
    }

    private RefundTerminalFactCheck exactRefundTerminalFact(
            MembershipPaymentRefundRequiredFinalizationCommand command,
            MembershipPaymentRefundTerminalFact fact) {
        boolean factPresent = fact != null;
        boolean callbackIdMatch = factPresent && Arrays.equals(
                fact.getCallbackId(),
                base64UrlCodec.decode(command.claim().callbackId()));
        boolean orderIdMatch = factPresent && Arrays.equals(
                fact.getOrderId(), base64UrlCodec.decode(command.orderId()));
        boolean callbackOrderMatch = callbackIdMatch && orderIdMatch;
        boolean callbackProviderTradeMatch = factPresent
                && Objects.equals(fact.getProviderTradeNo(), command.providerTradeNo());
        boolean callbackResolutionMatch = factPresent
                && MembershipPaymentCallbackResolution.REFUND_REQUIRED.name()
                        .equals(fact.getCallbackResolution());
        boolean orderTerminal = factPresent && terminalRefundStatus(fact.getOrderStatus());
        boolean entitlementRefundRequired = factPresent
                && fact.getOrderEntitlementResolution()
                        == MembershipOrderEntitlementResolution.REFUND_REQUIRED;
        boolean orderProviderTradePresent = factPresent
                && fact.getOrderProviderTradeNo() != null;
        String reason;
        if (!factPresent) {
            reason = "CALLBACK_FACT_MISSING";
        } else if (!callbackIdMatch) {
            reason = "CALLBACK_ID_MISMATCH";
        } else if (!orderIdMatch) {
            reason = "ORDER_ID_MISMATCH";
        } else if (!callbackProviderTradeMatch) {
            reason = "CALLBACK_PROVIDER_TRADE_MISMATCH";
        } else if (!callbackResolutionMatch) {
            reason = "CALLBACK_RESOLUTION_MISMATCH";
        } else if (!orderTerminal) {
            reason = "ORDER_NOT_TERMINAL";
        } else if (!entitlementRefundRequired) {
            reason = "ENTITLEMENT_NOT_REFUND_REQUIRED";
        } else if (orderProviderTradePresent) {
            reason = "ORDER_PROVIDER_TRADE_STILL_BOUND";
        } else {
            reason = "VERIFIED";
        }
        boolean verified = "VERIFIED".equals(reason);
        MembershipPaymentLifecycleDiagnostics.refundPreflight(
                refundProvider(command.providerTradeNo()),
                "database_terminal_fact",
                "missing",
                factPresent,
                callbackOrderMatch,
                callbackProviderTradeMatch,
                callbackResolutionMatch,
                orderTerminal,
                entitlementRefundRequired,
                orderProviderTradePresent,
                verified ? "verified" : "failed",
                reason,
                MembershipPaymentTraceContext.currentTraceId());
        return new RefundTerminalFactCheck(verified, reason);
    }

    /** 日志中的 Provider 只能来自受控交易引用；旧数据或非法引用降级为 unavailable，不能影响退款裁决。 */
    private static PaymentProviderType refundProvider(String providerTradeNo) {
        try {
            return PaymentProviderReference.resolveTrade(providerTradeNo);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 历史恢复日志只消费已经归一化的枚举和布尔事实，禁止携带订单或流水原值。 */
    private static void logRefundRecovery(
            Collection<RefundRecoveryWork> recoveries,
            String outcome,
            String reason) {
        recoveries.stream().distinct().forEach(recovery ->
                MembershipPaymentLifecycleDiagnostics.refundRecovery(
                        recovery.provider(),
                        recovery.orderEntitlementClass(),
                        recovery.orderProviderTradePresent(),
                        outcome,
                        reason,
                        MembershipPaymentTraceContext.currentTraceId()));
    }

    private static String entitlementClass(
            MembershipOrderEntitlementResolution resolution) {
        if (resolution == null) {
            return "missing";
        }
        return switch (resolution) {
            case NOT_GRANTED -> "not_granted";
            case REFUND_REQUIRED -> "refund_required";
            default -> "unexpected";
        };
    }

    /** 将内部异常归一化为低基数阶段和固定原因，避免把异常正文写入重试日志。 */
    private static CallbackFailureClassification classifyFailure(
            RuntimeException exception) {
        if (exception instanceof SafeCallbackFailure safeFailure) {
            Throwable cause = safeFailure.getCause();
            String exceptionClass = cause == null
                    ? SafeCallbackFailure.class.getSimpleName()
                    : cause.getClass().getSimpleName();
            return new CallbackFailureClassification(
                    safeFailure.stage(),
                    safeFailure.reason(),
                    exceptionClass);
        }
        return new CallbackFailureClassification(
                "processing",
                "PROCESSING_FAILED",
                exception.getClass().getSimpleName());
    }

    private static boolean terminalRefundStatus(MembershipOrderStatus status) {
        return status == MembershipOrderStatus.CLOSED
                || status == MembershipOrderStatus.CANCELLED;
    }

    /**
     * REJECTED 必须在发布恢复消息前释放自己的 Marker；claim 代次不匹配或外来 Marker 会使整批重排。
     */
    private void releaseRejected(
            Collection<MembershipPaymentRejectedCallbackReleaseCommand> commands) {
        List<MembershipPaymentRejectedCallbackReleaseCommand> distinct = commands.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        command -> command.claim().callbackId(),
                        command -> command,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        if (distinct.isEmpty()) {
            return;
        }
        Set<String> expected = distinct.stream()
                .map(command -> command.claim().callbackId())
                .collect(Collectors.toSet());
        Set<String> released = unappliedCallbackStore.releaseRejected(distinct);
        if (!released.equals(expected)) {
            throw new IllegalStateException(
                    "Membership rejected callback release result is incomplete.");
        }
    }

    /**
     * REJECTED 的 Marker 已由原子脚本释放，恢复消息只等待真实业务边界；processing claim 在发布成功前继续提供崩溃恢复。
     */
    private void resumeRejectedOrders(Collection<MembershipOrderSnapshot> orders) {
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

    private static RefundAttemptWork refundAttemptWork(
            Map<String, PaymentCallbackClaim> claimsById,
            PaymentCallbackSnapshot callback) {
        return new RefundAttemptWork(
                requiredClaim(claimsById, callback.callbackId()).callbackId(),
                refundCommand(callback));
    }

    private MembershipPaymentRefundRequiredFinalizationCommand refundFinalizationCommand(
            Map<String, PaymentCallbackClaim> claimsById,
            PaymentCallbackSnapshot callback,
            MembershipOrderSnapshot order,
            OffsetDateTime resolvedAt) {
        return new MembershipPaymentRefundRequiredFinalizationCommand(
                requiredClaim(claimsById, callback.callbackId()),
                callback.orderId(),
                callback.providerTradeNo(),
                order.expiresAt().plus(closingDuration),
                resolvedAt);
    }

    private static MembershipPaymentRejectedCallbackReleaseCommand rejectedReleaseCommand(
            Map<String, PaymentCallbackClaim> claimsById,
            PaymentCallbackSnapshot callback) {
        return new MembershipPaymentRejectedCallbackReleaseCommand(
                requiredClaim(claimsById, callback.callbackId()),
                callback.orderId());
    }

    private static PaymentCallbackClaim requiredClaim(
            Map<String, PaymentCallbackClaim> claimsById,
            String callbackId) {
        PaymentCallbackClaim claim = claimsById.get(callbackId);
        if (claim == null) {
            throw new IllegalStateException("Payment callback claim is missing.");
        }
        return claim;
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
     * 未应用结果会在恢复 MQ 或退款前原子重置模拟 Provider；complete 保留相同的精确 callback 校验作为幂等兜底。
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

    /** 该值对象只保存历史终态重放所需的脱敏诊断分类。 */
    private record RefundRecoveryWork(
            PaymentProviderType provider,
            String orderEntitlementClass,
            boolean orderProviderTradePresent) {
    }

    /** 该工作项把 callbackId 与退款命令绑定，Redis 协调键不得由订单或第三方流水替代。 */
    private record RefundAttemptWork(String callbackId, PaymentRefundCommand command) {
    }

    /** 该检查结果同时携带终态可信度和固定失败原因，供预检异常安全分类。 */
    private record RefundTerminalFactCheck(boolean verified, String reason) {
    }

    /** 该值对象把失败阶段、固定原因和异常类型绑定后交给结构化日志。 */
    private record CallbackFailureClassification(
            String stage,
            String reason,
            String exceptionClass) {
    }

    /** 该安全异常仅跨编排层传递白名单分类，故意不承载外部异常正文或堆栈。 */
    private static final class SafeCallbackFailure extends RuntimeException {

        private final String stage;
        private final String reason;

        private SafeCallbackFailure(
                String stage,
                String reason,
                RuntimeException cause) {
            super(null, cause, false, false);
            this.stage = stage;
            this.reason = reason;
        }

        private String stage() {
            return stage;
        }

        private String reason() {
            return reason;
        }
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
