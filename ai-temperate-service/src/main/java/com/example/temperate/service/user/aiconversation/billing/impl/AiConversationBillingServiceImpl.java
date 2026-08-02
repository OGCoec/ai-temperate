package com.example.temperate.service.user.aiconversation.billing.impl;

import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiConversation;
import com.example.temperate.model.ai.entity.AiModelUsage;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.billing.AiConversationBillingService;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservationCommand;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在调用上游之前用单个 PostgreSQL 短事务创建会话、锁定额度、预扣最大费用并建立 usage 幂等占位。
 *
 * <p>该实现绝不执行网络调用；事务提交后才允许编排层连接 8317，从而避免流式响应长期占用数据库连接。</p>
 */
@Service
public final class AiConversationBillingServiceImpl
        implements AiConversationBillingService {

    private final AiConversationMapper conversationMapper;
    private final AiModelUsageMapper usageMapper;
    private final AiModelUsageDetailMapper detailMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final HybridSemaphoreIdWorker idWorker;
    private final AiConversationQuotaCalculator quotaCalculator;
    private final MembershipQuotaPlanService quotaPlanService;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public AiConversationBillingServiceImpl(
            AiConversationMapper conversationMapper,
            AiModelUsageMapper usageMapper,
            AiModelUsageDetailMapper detailMapper,
            UserMembershipQuotaMapper quotaMapper,
            HybridSemaphoreIdWorker idWorker,
            AiConversationQuotaCalculator quotaCalculator,
            MembershipQuotaPlanService quotaPlanService,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            Clock clock,
            AiConversationMetrics metrics) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.detailMapper = Objects.requireNonNull(detailMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.quotaCalculator = Objects.requireNonNull(quotaCalculator);
        this.quotaPlanService = Objects.requireNonNull(quotaPlanService);
        this.cacheInvalidationExecutor =
                Objects.requireNonNull(cacheInvalidationExecutor);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    @Transactional
    public AiConversationReservation reserve(
            AiConversationReservationCommand command) {
        Objects.requireNonNull(command);
        // 相同幂等摘要必须先在事务级 advisory lock 上串行化，避免两个请求同时通过查重并重复预扣。
        detailMapper.acquireIdempotencyLock(
                idempotencyLockKey(command.idempotencyDigest()));
        AiModelUsageDetail duplicate = detailMapper.findByIdempotencyDigest(
                command.idempotencyDigest());
        if (duplicate != null) {
            return replay(command, duplicate);
        }

        byte[] conversationId = command.conversationId();
        boolean newConversation = conversationId == null;
        if (newConversation) {
            conversationId = idWorker.nextId();
            AiConversation conversation = new AiConversation();
            conversation.setId(conversationId);
            conversation.setLoginIdentityId(command.userId());
            conversation.setActive(true);
            if (conversationMapper.insert(conversation) != 1) {
                throw new IllegalStateException(
                        "AI conversation insert did not affect one row.");
            }
        } else if (conversationMapper.findActiveOwned(
                conversationId, command.userId()) == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                    "会话不存在或不可用",
                    false);
        }

        AiModelCacheEntry model = command.model();
        long reservedQuota = quotaCalculator.reservedQuota(
                command.estimatedPromptTokens(),
                model.maxOutputTokens(),
                model.inputRatio(),
                model.outputRatio());
        UserMembershipQuota quota =
                quotaMapper.findByLoginIdentityIdForUpdate(command.userId());
        if (quota == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_QUOTA_INSUFFICIENT,
                    "当前账号没有可用额度记录",
                    false);
        }
        activateExpiredPeriod(quota);
        if (quota.getQuotaBalanceMinor() < reservedQuota) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_QUOTA_INSUFFICIENT,
                    "当前额度不足以开始本次模型调用",
                    false);
        }
        quota.setQuotaBalanceMinor(
                Math.subtractExact(quota.getQuotaBalanceMinor(), reservedQuota));
        if (quotaMapper.updateBalanceAndPeriod(quota) != 1) {
            throw new IllegalStateException(
                    "AI quota reservation did not affect one row.");
        }

        byte[] usageId = idWorker.nextId();
        AiModelUsage usage = new AiModelUsage();
        usage.setId(usageId);
        usage.setLoginIdentityId(command.userId());
        usage.setAiModelId(model.id());
        usage.setBillingStatus(AiModelBillingStatus.RESERVED.code());
        if (usageMapper.insert(usage) != 1) {
            throw new IllegalStateException(
                    "AI model usage insert did not affect one row.");
        }

        AiModelUsageDetail detail = new AiModelUsageDetail();
        detail.setUsageId(usageId);
        detail.setConversationId(conversationId);
        detail.setIdempotencyKeyDigest(command.idempotencyDigest());
        detail.setVendorSnapshot(model.vendor());
        detail.setStream(true);
        detail.setEstimatedPromptTokens(command.estimatedPromptTokens());
        detail.setMaxOutputTokens(model.maxOutputTokens());
        detail.setInputRatioSnapshot(model.inputRatio());
        detail.setCachedInputRatioSnapshot(model.cachedInputRatio());
        detail.setOutputRatioSnapshot(model.outputRatio());
        detail.setReservedQuotaMinor(reservedQuota);
        if (detailMapper.insert(detail) != 1) {
            throw new IllegalStateException(
                    "AI model usage detail insert did not affect one row.");
        }
        cacheInvalidationExecutor.evictAfterCommit(command.userId());
        metrics.billing("reserve", "success");
        return new AiConversationReservation(
                conversationId,
                usageId,
                null,
                AiModelBillingStatus.RESERVED.code(),
                reservedQuota,
                command.estimatedPromptTokens(),
                model.maxOutputTokens(),
                model.inputRatio(),
                model.cachedInputRatio(),
                model.outputRatio(),
                newConversation,
                false);
    }

    private AiConversationReservation replay(
            AiConversationReservationCommand command,
            AiModelUsageDetail detail) {
        AiModelUsage usage = usageMapper.findByIdForUpdate(detail.getUsageId());
        if (usage == null
                || usage.getLoginIdentityId() != command.userId()
                || usage.getAiModelId() != command.model().id()
                || (command.conversationId() != null
                && !java.util.Arrays.equals(
                        command.conversationId(), detail.getConversationId()))) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_IDEMPOTENCY_CONFLICT,
                    "幂等键已用于另一项模型请求",
                    false);
        }
        return new AiConversationReservation(
                detail.getConversationId(),
                detail.getUsageId(),
                detail.getConversationMessageId(),
                usage.getBillingStatus(),
                detail.getReservedQuotaMinor(),
                detail.getEstimatedPromptTokens(),
                detail.getMaxOutputTokens(),
                detail.getInputRatioSnapshot(),
                detail.getCachedInputRatioSnapshot(),
                detail.getOutputRatioSnapshot(),
                false,
                true);
    }

    void activateExpiredPeriod(UserMembershipQuota quota) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (quota.getQuotaPeriodEndsAt() != null
                && quota.getQuotaPeriodEndsAt().isAfter(now)) {
            return;
        }
        MembershipTier tier = resolveTier(quota.getMembershipTier());
        if (tier == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_QUOTA_RULE_MISSING,
                    "当前会员等级尚未配置额度周期规则",
                    false);
        }
        // 该方法只在额度行已被 FOR UPDATE 锁定的预扣事务中执行，确保周期重置与本次扣减原子落库。
        MembershipQuotaPlan plan = quotaPlanService.getRequired(tier);
        quota.setQuotaBalanceMinor(plan.totalMinor());
        quota.setQuotaPeriodStartedAt(now);
        quota.setQuotaPeriodEndsAt(now.plus(plan.period()));
    }

    private static MembershipTier resolveTier(Integer membershipTierCode) {
        if (membershipTierCode == null
                || membershipTierCode < 0
                || membershipTierCode >= MembershipTier.values().length) {
            return null;
        }
        return MembershipTier.values()[membershipTierCode];
    }

    private static long idempotencyLockKey(byte[] digest) {
        if (digest == null || digest.length < Long.BYTES) {
            throw new IllegalArgumentException(
                    "AI conversation idempotency digest is invalid.");
        }
        return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    }
}
