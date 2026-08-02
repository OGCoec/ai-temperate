package com.example.temperate.service.user.aiconversation.reconciliation.impl;

import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiModelUsageRefundCandidate;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTimed;
import com.example.temperate.service.user.aiconversation.reconciliation.AiConversationReconciliationService;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用单条有界 PostgreSQL 更新识别遗留预扣，避免在未知上游费用时错误退款。
 */
@Service
public final class AiConversationReconciliationServiceImpl
        implements AiConversationReconciliationService {

    private static final String FAILURE_CODE = "AI_RESERVED_EXPIRED";
    private static final List<String> AUTO_REFUND_FAILURE_CODES = List.of(
            "AI_STREAM_TERMINATED_WITHOUT_USAGE",
            "AI_UPSTREAM_TIMEOUT",
            "AI_UPSTREAM_STREAM_FAILED",
            "AI_UPSTREAM_UNAVAILABLE",
            "AI_USAGE_UNAVAILABLE");

    private final AiModelUsageMapper usageMapper;
    private final AiModelUsageDetailMapper detailMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final AiConversationProperties conversationProperties;
    private final AiInferenceProperties inferenceProperties;
    private final Clock clock;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final AiConversationMetrics metrics;

    public AiConversationReconciliationServiceImpl(
            AiModelUsageMapper usageMapper,
            AiModelUsageDetailMapper detailMapper,
            UserMembershipQuotaMapper quotaMapper,
            AiConversationProperties conversationProperties,
            AiInferenceProperties inferenceProperties,
            Clock clock,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            AiConversationMetrics metrics) {
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.detailMapper = Objects.requireNonNull(detailMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.conversationProperties = Objects.requireNonNull(
                conversationProperties);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.clock = Objects.requireNonNull(clock);
        this.cacheInvalidationExecutor = Objects.requireNonNull(
                cacheInvalidationExecutor);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "RECONCILIATION_EXPIRED_RESERVATIONS")
    public int reconcileExpiredReservations() {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        OffsetDateTime cutoff = now
                .minus(inferenceProperties.maxStreamDuration())
                .minus(conversationProperties.reservationSafetyBuffer());
        // 这里只改变结算状态，不自动退款；请求可能已产生上游费用但进程未收到最终 Usage。
        int updated = usageMapper.markExpiredReservationsForReconciliation(
                AiModelBillingStatus.RESERVED.code(),
                AiModelBillingStatus.RECONCILE_REQUIRED.code(),
                cutoff,
                conversationProperties.reconciliationBatchSize(),
                FAILURE_CODE,
                now);
        if (updated > 0) {
            metrics.billing("reconcile", "success", updated);
        }
        return updated;
    }

    @Override
    @Transactional
    @AiConversationLifecycleTimed(stage = "RECONCILIATION_HISTORICAL_REFUND")
    public int refundHistoricalSystemFailures() {
        if (!conversationProperties
                .historicalSystemFailureAutoRefundEnabled()) {
            return 0;
        }
        List<AiModelUsageRefundCandidate> candidates =
                usageMapper.findSystemFailureRefundCandidatesForUpdate(
                        AiModelBillingStatus.RECONCILE_REQUIRED.code(),
                        AUTO_REFUND_FAILURE_CODES,
                        conversationProperties.reconciliationBatchSize());
        if (candidates.isEmpty()) {
            return 0;
        }
        List<Long> userIds = candidates.stream()
                .map(AiModelUsageRefundCandidate::loginIdentityId)
                .distinct()
                .toList();
        // 四个批量步骤必须在同一 PostgreSQL 事务内影响完整候选集；任一计数不符都会抛错并整批回滚。
        requireAffected(
                "quota refund",
                userIds.size(),
                quotaMapper.addHistoricalAiRefunds(candidates));
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        requireAffected(
                "usage refund status",
                candidates.size(),
                usageMapper.markHistoricalSystemFailuresRefunded(
                        candidates,
                        AiModelBillingStatus.RECONCILE_REQUIRED.code(),
                        AiModelBillingStatus.REFUNDED.code(),
                        now));
        requireAffected(
                "usage detail refund",
                candidates.size(),
                detailMapper.finalizeHistoricalRefunds(candidates));
        cacheInvalidationExecutor.evictAfterCommit(userIds);
        metrics.billing("refund", "success", candidates.size());
        return candidates.size();
    }

    private static void requireAffected(
            String operation, int expected, int actual) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Historical AI " + operation + " affected " + actual
                            + " rows instead of " + expected + ".");
        }
    }
}
