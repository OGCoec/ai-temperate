package com.example.temperate.service.user.aiconversation.billing.impl;

import com.example.temperate.mapper.ai.AiConversationMessageMapper;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiConversationMessage;
import com.example.temperate.model.ai.entity.AiModelUsage;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementCommand;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementResult;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleEvent;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTimed;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiConversationProviderCostUsage;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在上游结束后以单个 PostgreSQL 短事务完成多退少补、完整消息插入以及 usage/detail 最终状态更新。
 *
 * <p>正常成功只接受上游最终 Usage；用户主动取消可以按已展示文本保守估算，系统超时、限流和断流则通过
 * 独立退款入口返还全部预扣。只有数据库终态无法确认时才保留预扣并进入待对账。</p>
 */
@Service
public final class AiConversationSettlementServiceImpl
        implements AiConversationSettlementService {

    private final AiModelUsageMapper usageMapper;
    private final AiModelUsageDetailMapper detailMapper;
    private final AiConversationMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final AiConversationQuotaCalculator quotaCalculator;
    private final AiConversationProviderCostQuotaCalculator providerCostQuotaCalculator;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AiConversationMetrics metrics;
    private final AiConversationLifecycleDiagnosticService diagnostics;

    public AiConversationSettlementServiceImpl(
            AiModelUsageMapper usageMapper,
            AiModelUsageDetailMapper detailMapper,
            AiConversationMessageMapper messageMapper,
            AiConversationMapper conversationMapper,
            UserMembershipQuotaMapper quotaMapper,
            AiConversationQuotaCalculator quotaCalculator,
            AiConversationProviderCostQuotaCalculator providerCostQuotaCalculator,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            ObjectMapper objectMapper,
            Clock clock,
            AiConversationMetrics metrics,
            AiConversationLifecycleDiagnosticService diagnostics) {
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.detailMapper = Objects.requireNonNull(detailMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.quotaCalculator = Objects.requireNonNull(quotaCalculator);
        this.providerCostQuotaCalculator =
                Objects.requireNonNull(providerCostQuotaCalculator);
        this.cacheInvalidationExecutor =
                Objects.requireNonNull(cacheInvalidationExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    public long reserveMessageId() {
        long messageId = messageMapper.reserveMessageId();
        if (messageId <= 0L) {
            throw new IllegalStateException("Reserved conversation message ID is invalid.");
        }
        return messageId;
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "SETTLEMENT_COMPLETE")
    public AiConversationSettlementResult complete(
            AiConversationSettlementCommand command) {
        AiConversationLifecycleTraceContext traceContext =
                effectiveContext(command.traceContext());
        TransactionObservation transaction = observeTransaction(traceContext);
        AiModelUsage usage = requiredReservedUsage(command.usageId());
        AiModelUsageDetail detail = requiredDetail(command.usageId());
        QuotaSettlement quotaSettlement =
                settleQuota(usage, detail, command);
        diagnostics.record(traceContext, "QUOTA_UPDATE_COMPLETED");

        AiConversationMessage message = persistMessage(detail, command);

        AiModelBillingStatus finalStatus = quotaSettlement.settled()
                ? AiModelBillingStatus.SETTLED
                : AiModelBillingStatus.RECONCILE_REQUIRED;
        long chargedQuota = quotaSettlement.settled()
                ? quotaSettlement.actualQuota()
                : detail.getReservedQuotaMinor();
        settleUsage(
                usage,
                finalStatus,
                command,
                chargedQuota,
                quotaSettlement.settled()
                        ? null
                        : "AI_SETTLEMENT_RECONCILE_REQUIRED");
        diagnostics.record(traceContext, "USAGE_UPDATE_COMPLETED");
        finalizeDetail(
                detail,
                message.getId(),
                command.upstreamRequestId(),
                quotaSettlement.delta());
        diagnostics.record(traceContext, "DETAIL_UPDATE_COMPLETED");
        if (!quotaSettlement.settled()) {
            diagnostics.record(
                    traceContext,
                    "RECONCILE_REQUIRED_MARKED",
                    billingEvent(
                            AiModelBillingStatus.RECONCILE_REQUIRED.name(),
                            null));
        }
        // 预扣后用户可能重新读取过余额，因此最终结算无论差额是否为零都再次提交后失效资料缓存。
        cacheInvalidationExecutor.evictAfterCommit(
                usage.getLoginIdentityId());
        recordSettlementMetric(quotaSettlement);
        transaction.complete(finalStatus.name());
        return new AiConversationSettlementResult(
                message.getId(),
                chargedQuota,
                quotaSettlement.delta(),
                !quotaSettlement.settled());
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "SETTLEMENT_COMPLETE_RECONCILE")
    public AiConversationSettlementResult completeReconcile(
            AiConversationSettlementCommand command,
            String failureCode) {
        AiConversationLifecycleTraceContext traceContext =
                effectiveContext(command.traceContext());
        TransactionObservation transaction = observeTransaction(traceContext);
        AiModelUsage usage = requiredReservedUsage(command.usageId());
        AiModelUsageDetail detail = requiredDetail(command.usageId());
        if (usage.getMeteringBasis() == null
                || detail.getMeteringBasis() == null
                || usage.getMeteringBasis()
                != AiConversationMeteringBasis.PROVIDER_COST_TICKS.code()
                || detail.getMeteringBasis()
                != AiConversationMeteringBasis.PROVIDER_COST_TICKS.code()
                || command.usage() != null
                || !("AI_IMAGE_COST_EVIDENCE_MISSING".equals(failureCode)
                        || "AI_VIDEO_COST_EVIDENCE_MISSING".equals(failureCode))) {
            throw new IllegalArgumentException(
                    "Cost-evidence reconciliation requires a provider-cost reservation without fabricated usage.");
        }
        AiConversationMessage message = persistMessage(detail, command);
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (usageMapper.settle(
                usage.getId(),
                AiModelBillingStatus.RESERVED.code(),
                AiModelBillingStatus.RECONCILE_REQUIRED.code(),
                null,
                null,
                null,
                null,
                null,
                detail.getReservedQuotaMinor(),
                command.finishReason(),
                failureCode,
                now) != 1) {
            throw new IllegalStateException(
                    "AI reconciliation usage update did not affect one row.");
        }
        // 成本证据不完整时保留全部预扣，delta 必须保持 NULL，避免被历史退款流程误认成可退余额。
        finalizeDetail(
                detail,
                message.getId(),
                command.upstreamRequestId(),
                null);
        cacheInvalidationExecutor.evictAfterCommit(usage.getLoginIdentityId());
        metrics.billing("reconcile", "success");
        diagnostics.record(traceContext, "RECONCILE_REQUIRED_MARKED");
        transaction.complete(AiModelBillingStatus.RECONCILE_REQUIRED.name());
        return new AiConversationSettlementResult(
                message.getId(),
                detail.getReservedQuotaMinor(),
                0L,
                true);
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "SETTLEMENT_INTERRUPTED")
    public AiConversationSettlementResult settleInterrupted(
            AiConversationSettlementCommand command) {
        AiConversationLifecycleTraceContext traceContext =
                effectiveContext(command.traceContext());
        TransactionObservation transaction = observeTransaction(traceContext);
        AiModelUsage usage = requiredReservedUsage(command.usageId());
        AiModelUsageDetail detail = requiredDetail(command.usageId());
        QuotaSettlement quotaSettlement =
                settleQuota(usage, detail, command);
        diagnostics.record(traceContext, "QUOTA_UPDATE_COMPLETED");
        AiModelBillingStatus finalStatus = quotaSettlement.settled()
                ? AiModelBillingStatus.SETTLED
                : AiModelBillingStatus.RECONCILE_REQUIRED;
        long chargedQuota = quotaSettlement.settled()
                ? quotaSettlement.actualQuota()
                : detail.getReservedQuotaMinor();
        settleUsage(
                usage,
                finalStatus,
                command,
                chargedQuota,
                quotaSettlement.settled()
                        ? command.finishReason()
                        : "AI_SETTLEMENT_RECONCILE_REQUIRED");
        diagnostics.record(traceContext, "USAGE_UPDATE_COMPLETED");
        finalizeDetail(
                detail,
                null,
                command.upstreamRequestId(),
                quotaSettlement.delta());
        diagnostics.record(traceContext, "DETAIL_UPDATE_COMPLETED");
        if (!quotaSettlement.settled()) {
            diagnostics.record(
                    traceContext,
                    "RECONCILE_REQUIRED_MARKED",
                    billingEvent(
                            AiModelBillingStatus.RECONCILE_REQUIRED.name(),
                            null));
        }
        cacheInvalidationExecutor.evictAfterCommit(
                usage.getLoginIdentityId());
        recordSettlementMetric(quotaSettlement);
        transaction.complete(finalStatus.name());
        return new AiConversationSettlementResult(
                0L,
                chargedQuota,
                quotaSettlement.delta(),
                !quotaSettlement.settled());
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "SETTLEMENT_REFUND")
    public void refundFailed(byte[] usageId, String failureCode) {
        refundFailedInternal(usageId, "UPSTREAM_FAILED", failureCode);
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "SETTLEMENT_REFUND")
    public void refundFailed(
            byte[] usageId,
            String finishReason,
            String failureCode) {
        refundFailedInternal(usageId, finishReason, failureCode);
    }

    private void refundFailedInternal(
            byte[] usageId,
            String finishReason,
            String failureCode) {
        AiConversationLifecycleTraceContext traceContext =
                diagnostics.currentContext();
        TransactionObservation transaction = observeTransaction(traceContext);
        AiModelUsage usage = requiredReservedUsage(usageId);
        AiModelUsageDetail detail = requiredDetail(usageId);
        UserMembershipQuota quota =
                quotaMapper.findByLoginIdentityIdForUpdate(
                        usage.getLoginIdentityId());
        if (quota == null) {
            throw new IllegalStateException("AI settlement quota row is missing.");
        }
        quota.setQuotaBalanceMinor(Math.addExact(
                quota.getQuotaBalanceMinor(),
                detail.getReservedQuotaMinor()));
        if (quotaMapper.updateBalanceAndPeriod(quota) != 1) {
            throw new IllegalStateException("AI quota refund did not affect one row.");
        }
        diagnostics.record(traceContext, "QUOTA_UPDATE_COMPLETED");
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (usageMapper.settle(
                usageId,
                AiModelBillingStatus.RESERVED.code(),
                AiModelBillingStatus.FAILED_REFUNDED.code(),
                null,
                null,
                null,
                null,
                null,
                0L,
                finishReason,
                failureCode,
                now) != 1) {
            throw new IllegalStateException(
                    "AI failed refund status update did not affect one row.");
        }
        diagnostics.record(traceContext, "USAGE_UPDATE_COMPLETED");
        finalizeDetail(
                detail,
                null,
                null,
                -detail.getReservedQuotaMinor());
        diagnostics.record(traceContext, "DETAIL_UPDATE_COMPLETED");
        cacheInvalidationExecutor.evictAfterCommit(
                usage.getLoginIdentityId());
        metrics.billing("refund", "success");
        transaction.complete(AiModelBillingStatus.FAILED_REFUNDED.name());
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "SETTLEMENT_RECONCILE")
    public void markReconcileRequired(byte[] usageId, String failureCode) {
        AiConversationLifecycleTraceContext traceContext =
                diagnostics.currentContext();
        TransactionObservation transaction = observeTransaction(traceContext);
        AiModelUsage usage = requiredReservedUsage(usageId);
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (usageMapper.settle(
                usageId,
                AiModelBillingStatus.RESERVED.code(),
                AiModelBillingStatus.RECONCILE_REQUIRED.code(),
                null,
                null,
                null,
                null,
                null,
                null,
                "INTERRUPTED",
                failureCode,
                now) != 1) {
            throw new IllegalStateException(
                    "AI reconciliation status update did not affect one row.");
        }
        diagnostics.record(traceContext, "USAGE_UPDATE_COMPLETED");
        metrics.billing("reconcile", "success");
        diagnostics.record(
                traceContext,
                "RECONCILE_REQUIRED_MARKED",
                billingEvent(AiModelBillingStatus.RECONCILE_REQUIRED.name(), null));
        transaction.complete(AiModelBillingStatus.RECONCILE_REQUIRED.name());
    }

    private AiConversationLifecycleTraceContext effectiveContext(
            AiConversationLifecycleTraceContext commandContext) {
        if (commandContext != null
                && !"unavailable".equals(commandContext.traceId())) {
            return commandContext;
        }
        return diagnostics.currentContext();
    }

    private TransactionObservation observeTransaction(
            AiConversationLifecycleTraceContext context) {
        long startedNanos = System.nanoTime();
        AtomicReference<String> billingStatus =
                new AtomicReference<>("unavailable");
        AtomicBoolean committed = new AtomicBoolean();
        diagnostics.record(context, "SETTLEMENT_TRANSACTION_ENTERED");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 只有事务管理器的 afterCommit 才能证明 PostgreSQL 已提交，AOP 方法返回不能替代这一事实。
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            committed.set(true);
                            diagnostics.record(
                                    context,
                                    "SETTLEMENT_TRANSACTION_COMMITTED",
                                    billingEvent(
                                            billingStatus.get(),
                                            elapsedMillis(startedNanos)));
                        }

                        @Override
                        public void afterCompletion(int status) {
                            if (!committed.get()
                                    && status == TransactionSynchronization
                                            .STATUS_ROLLED_BACK) {
                                diagnostics.record(
                                        context,
                                        "SETTLEMENT_TRANSACTION_ROLLED_BACK",
                                        billingEvent(
                                                billingStatus.get(),
                                                elapsedMillis(startedNanos)));
                            } else if (!committed.get()) {
                                diagnostics.record(
                                        context,
                                        "SETTLEMENT_TRANSACTION_COMPLETION_UNKNOWN",
                                        billingEvent(
                                                billingStatus.get(),
                                                elapsedMillis(startedNanos)));
                            }
                        }
                    });
        }
        return status -> {
            billingStatus.set(status);
            diagnostics.record(
                    context,
                    "SETTLEMENT_DB_WRITES_COMPLETED",
                    billingEvent(status, elapsedMillis(startedNanos)));
        };
    }

    private static AiConversationLifecycleEvent billingEvent(
            String billingStatus,
            Long durationMs) {
        return new AiConversationLifecycleEvent(
                null,
                null,
                null,
                null,
                null,
                billingStatus,
                null,
                null,
                null,
                null,
                durationMs,
                null,
                null,
                null);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private AiModelUsage requiredReservedUsage(byte[] usageId) {
        AiModelUsage usage = usageMapper.findByIdForUpdate(usageId);
        if (usage == null
                || usage.getBillingStatus()
                != AiModelBillingStatus.RESERVED.code()) {
            throw new IllegalStateException(
                    "AI model usage is missing or no longer reserved.");
        }
        return usage;
    }

    private AiModelUsageDetail requiredDetail(byte[] usageId) {
        AiModelUsageDetail detail = detailMapper.findByUsageId(usageId);
        if (detail == null) {
            throw new IllegalStateException("AI model usage detail is missing.");
        }
        return detail;
    }

    private void settleUsage(
            AiModelUsage usage,
            AiModelBillingStatus status,
            AiConversationSettlementCommand command,
            Long chargedQuota,
            String failureCode) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        AiConversationUsage tokenUsage = command.usage() instanceof AiConversationUsage value
                ? value
                : null;
        AiConversationProviderCostUsage providerUsage =
                command.usage() instanceof AiConversationProviderCostUsage value
                        ? value
                        : null;
        if (usageMapper.settle(
                usage.getId(),
                AiModelBillingStatus.RESERVED.code(),
                status.code(),
                tokenUsage == null ? null : tokenUsage.promptTokens(),
                tokenUsage == null ? null : tokenUsage.completionTokens(),
                tokenUsage == null ? null : tokenUsage.cachedPromptTokens(),
                tokenUsage == null ? null : tokenUsage.reasoningTokens(),
                providerUsage == null ? null : providerUsage.costInUsdTicks(),
                chargedQuota,
                command.finishReason(),
                failureCode,
                now) != 1) {
            throw new IllegalStateException(
                    "AI model usage settlement did not affect one row.");
        }
    }

    private QuotaSettlement settleQuota(
            AiModelUsage usage,
            AiModelUsageDetail detail,
            AiConversationSettlementCommand command) {
        if (command.usage() == null
                || usage.getMeteringBasis() == null
                || detail.getMeteringBasis() == null
                || usage.getMeteringBasis() != command.usage().basis().code()
                || detail.getMeteringBasis() != command.usage().basis().code()) {
            throw new IllegalArgumentException(
                    "Settlement usage does not match the reserved metering basis.");
        }
        UserMembershipQuota quota =
                quotaMapper.findByLoginIdentityIdForUpdate(
                        usage.getLoginIdentityId());
        if (quota == null) {
            throw new IllegalStateException("AI settlement quota row is missing.");
        }
        long actualQuota;
        if (command.usage() instanceof AiConversationUsage tokenUsage) {
            actualQuota = quotaCalculator.actualQuota(
                    tokenUsage.promptTokens(),
                    tokenUsage.cachedPromptTokens(),
                    tokenUsage.completionTokens(),
                    detail.getInputRatioSnapshot(),
                    detail.getCachedInputRatioSnapshot(),
                    detail.getOutputRatioSnapshot());
        } else if (command.usage() instanceof AiConversationProviderCostUsage providerUsage) {
            // 结算必须与预扣使用同一 ticks→账户美分规则，差额才能正确退款或补扣。
            actualQuota = providerCostQuotaCalculator.actualQuotaMinor(
                    providerUsage.costInUsdTicks());
        } else {
            throw new IllegalArgumentException(
                    "Settlement usage basis is unsupported.");
        }
        long delta = Math.subtractExact(
                actualQuota, detail.getReservedQuotaMinor());
        long newBalance = Math.subtractExact(
                quota.getQuotaBalanceMinor(), delta);
        if (newBalance < 0L) {
            // 无法完成超额补扣时保留已预扣额度，由调用方在同一事务中保存最终 Usage 并标记待对账。
            return new QuotaSettlement(false, actualQuota, delta);
        }
        quota.setQuotaBalanceMinor(newBalance);
        if (quotaMapper.updateBalanceAndPeriod(quota) != 1) {
            throw new IllegalStateException(
                    "AI final quota settlement did not affect one row.");
        }
        return new QuotaSettlement(true, actualQuota, delta);
    }

    private void finalizeDetail(
            AiModelUsageDetail detail,
            Long messageId,
            String upstreamRequestId,
            Long delta) {
        if (detailMapper.finalizeDetail(
                detail.getUsageId(),
                messageId,
                upstreamRequestId,
                delta) != 1) {
            throw new IllegalStateException(
                    "AI model usage detail finalization did not affect one row.");
        }
    }

    private AiConversationMessage persistMessage(
            AiModelUsageDetail detail,
            AiConversationSettlementCommand command) {
        if (command.messageId() == null || command.messageId() <= 0L) {
            throw new IllegalArgumentException(
                    "Successful settlement requires a reserved message ID.");
        }
        AiConversationMessage message = new AiConversationMessage();
        message.setId(command.messageId());
        message.setConversationId(detail.getConversationId());
        message.setContentText(command.user().text());
        message.setContentAttachmentsJson(json(command.user().attachments()));
        message.setContentPartsJson(json(command.userSearchTokens()));
        message.setQuestionTokens(command.assistant().text());
        message.setResponseAttachmentsJson(json(command.assistant().attachments()));
        if (messageMapper.insert(message) != 1) {
            throw new IllegalStateException(
                    "AI conversation message insert did not affect one row.");
        }
        // 消息、计费与会话游标必须同事务提交，避免会话引用尚未完成账务状态的消息。
        if (conversationMapper.updateAfterPersistedMessage(
                detail.getConversationId(),
                message.getId(),
                initialTitle(command.user().text())) != 1) {
            throw new IllegalStateException(
                    "AI conversation last message update did not affect one row.");
        }
        return message;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AI conversation JSON serialization failed.", exception);
        }
    }

    private static String initialTitle(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String collapsed = text.strip().replaceAll("\\s+", " ");
        int count = collapsed.codePointCount(0, collapsed.length());
        if (count <= 80) {
            return collapsed;
        }
        int end = collapsed.offsetByCodePoints(0, 80);
        return collapsed.substring(0, end);
    }

    private void recordSettlementMetric(QuotaSettlement settlement) {
        if (!settlement.settled()) {
            metrics.billing("reconcile", "success");
        } else if (settlement.delta() > 0L) {
            metrics.billing("supplement", "success");
        } else if (settlement.delta() < 0L) {
            metrics.billing("refund", "success");
        }
    }

    private record QuotaSettlement(
            boolean settled,
            long actualQuota,
            long delta) {
    }

    @FunctionalInterface
    private interface TransactionObservation {
        void complete(String billingStatus);
    }
}
