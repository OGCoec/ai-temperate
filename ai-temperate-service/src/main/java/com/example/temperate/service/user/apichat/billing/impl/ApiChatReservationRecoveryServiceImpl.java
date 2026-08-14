package com.example.temperate.service.user.apichat.billing.impl;

import com.example.temperate.mapper.ai.AiModelApiUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelApiUsageMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiModelApiUsageRefundCandidate;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.apichat.billing.ApiChatReservationRecoveryService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来用 FOR UPDATE SKIP LOCKED 每批最多五百条锁定陈旧预扣，并按账号聚合更新额度、Usage 和 Detail，避免逐条数据库 I/O。
 */
@Service
public final class ApiChatReservationRecoveryServiceImpl
        implements ApiChatReservationRecoveryService {

    private final AiModelApiUsageMapper usageMapper;
    private final AiModelApiUsageDetailMapper detailMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final AiInferenceProperties inferenceProperties;
    private final AiConversationProperties conversationProperties;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ApiChatReservationRecoveryServiceImpl(
            AiModelApiUsageMapper usageMapper,
            AiModelApiUsageDetailMapper detailMapper,
            UserMembershipQuotaMapper quotaMapper,
            AiInferenceProperties inferenceProperties,
            AiConversationProperties conversationProperties,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.detailMapper = Objects.requireNonNull(detailMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.conversationProperties = Objects.requireNonNull(conversationProperties);
        this.cacheInvalidationExecutor = Objects.requireNonNull(cacheInvalidationExecutor);
        this.clock = Objects.requireNonNull(clock);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    @Transactional
    public int recoverExpiredReservations() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime cutoff = now.minus(inferenceProperties.maxStreamDuration())
                .minus(conversationProperties.reservationSafetyBuffer());
        int limit = Math.min(500, conversationProperties.reconciliationBatchSize());
        List<AiModelApiUsageRefundCandidate> candidates =
                usageMapper.findExpiredReservationsForUpdate(
                        AiModelBillingStatus.RESERVED.code(), cutoff, limit);
        if (candidates.isEmpty()) {
            meterRegistry.counter("api.chat.recovery", "result", "empty").increment();
            return 0;
        }
        // 三条批量 UPDATE 位于同一 PostgreSQL 本地事务，任一失败都会回滚额度和两张 Usage 表。
        int affectedAccounts = (int) candidates.stream()
                .map(AiModelApiUsageRefundCandidate::getLoginIdentityId)
                .distinct()
                .count();
        if (quotaMapper.addApiRefunds(candidates) != affectedAccounts
                || detailMapper.finalizeRefundsBatch(candidates) != candidates.size()
                || usageMapper.markRefundedBatch(
                candidates,
                AiModelBillingStatus.RESERVED.code(),
                AiModelBillingStatus.FAILED_REFUNDED.code(),
                "STALE_RESERVED_RECOVERED",
                now) != candidates.size()) {
            throw new IllegalStateException("Expired API reservation batch did not fully refund");
        }
        cacheInvalidationExecutor.evictAfterCommit(
                candidates.stream()
                        .map(AiModelApiUsageRefundCandidate::getLoginIdentityId)
                        .distinct()
                        .toList());
        meterRegistry.counter("api.chat.recovery", "result", "refunded")
                .increment(candidates.size());
        return candidates.size();
    }
}
