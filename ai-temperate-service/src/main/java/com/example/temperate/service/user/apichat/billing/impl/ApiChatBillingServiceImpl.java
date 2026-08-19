package com.example.temperate.service.user.apichat.billing.impl;

import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.ai.AiModelApiUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelApiUsageMapper;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiModelApiUsage;
import com.example.temperate.model.ai.entity.AiModelApiUsageDetail;
import com.example.temperate.model.ai.entity.ApiKeyReservationAuthorization;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.user.aiconversation.billing.impl.AiConversationQuotaCalculator;
import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceProtocol;
import com.example.temperate.service.user.aiinference.api.ApiInferenceReservation;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来保证预扣前再次锁定并验证 Key/账号/模型/映射，结算差额永不令余额为负，并以 RESERVED 条件更新实现终态幂等。
 */
@Service
public final class ApiChatBillingServiceImpl implements ApiChatBillingService {

    private static final int KEY_ENABLED = 1;
    private static final int ACCOUNT_ACTIVE = 0;
    private static final int MAPPING_ACTIVE = 1;

    private final UserApiKeyMapper apiKeyMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final AiModelApiUsageMapper usageMapper;
    private final AiModelApiUsageDetailMapper detailMapper;
    private final AiConversationQuotaCalculator quotaCalculator;
    private final MembershipQuotaPlanService quotaPlanService;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final HybridSemaphoreIdWorker idWorker;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ApiChatBillingServiceImpl(
            UserApiKeyMapper apiKeyMapper,
            UserMembershipQuotaMapper quotaMapper,
            AiModelApiUsageMapper usageMapper,
            AiModelApiUsageDetailMapper detailMapper,
            AiConversationQuotaCalculator quotaCalculator,
            MembershipQuotaPlanService quotaPlanService,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            HybridSemaphoreIdWorker idWorker,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.apiKeyMapper = Objects.requireNonNull(apiKeyMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.detailMapper = Objects.requireNonNull(detailMapper);
        this.quotaCalculator = Objects.requireNonNull(quotaCalculator);
        this.quotaPlanService = Objects.requireNonNull(quotaPlanService);
        this.cacheInvalidationExecutor = Objects.requireNonNull(cacheInvalidationExecutor);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.clock = Objects.requireNonNull(clock);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    @Transactional
    public ApiInferenceReservation reserve(
            ApiKeyPrincipal principal,
            ApiInferenceExecutionRequest request) {
        OffsetDateTime now = now();
        ApiKeyReservationAuthorization authorization =
                apiKeyMapper.findReservationAuthorizationForUpdate(
                        principal.apiKeyId(), request.model().id());
        validateAuthorization(authorization, principal, request, now);
        if (apiKeyMapper.touchLastUsed(principal.apiKeyId(), now) != 1) {
            throw new ApiChatException(
                    ApiChatErrorCode.INVALID_API_KEY,
                    "Invalid API Key.",
                    null);
        }

        long reservedMinor = quotaCalculator.reservedQuota(
                request.estimatedInputTokens(),
                request.effectiveMaxOutputTokens(),
                authorization.getInputRatio(),
                authorization.getOutputRatio());
        UserMembershipQuota quota = quotaMapper.findByLoginIdentityIdForUpdate(
                principal.loginIdentityId());
        if (quota == null) {
            throw insufficient(request.protocol());
        }
        activateExpiredPeriod(quota, now);
        if (quota.getQuotaBalanceMinor() < reservedMinor) {
            throw insufficient(request.protocol());
        }
        quota.setQuotaBalanceMinor(Math.subtractExact(
                quota.getQuotaBalanceMinor(), reservedMinor));
        if (quotaMapper.updateBalanceAndPeriod(quota) != 1) {
            throw new IllegalStateException("API quota reservation did not affect one row");
        }

        AiModelApiUsage usage = new AiModelApiUsage();
        usage.setId(nextHybridId());
        usage.setKeyDigest(principal.keyDigest());
        usage.setAiModelId(request.model().id());
        usage.setBillingStatus(AiModelBillingStatus.RESERVED.code());
        if (usageMapper.insert(usage) != 1
                || usage.getId() == null
                || usage.getId().length != 16) {
            throw new IllegalStateException("API model usage insert did not affect one row");
        }
        AiModelApiUsageDetail detail = new AiModelApiUsageDetail();
        detail.setId(nextHybridId());
        detail.setUsageId(usage.getId());
        detail.setVendorSnapshot(request.model().vendor());
        detail.setStream(request.stream());
        detail.setReservedQuotaMinor(reservedMinor);
        if (detailMapper.insert(detail) != 1) {
            throw new IllegalStateException("API model usage detail insert did not affect one row");
        }
        cacheInvalidationExecutor.evictAfterCommit(principal.loginIdentityId());
        count(request.protocol(), "reserve", "success");
        return new ApiInferenceReservation(
                usage.getId(),
                principal.loginIdentityId(),
                principal.apiKeyId(),
                reservedMinor,
                request.estimatedInputTokens(),
                authorization.getInputRatio(),
                authorization.getCachedInputRatio(),
                authorization.getOutputRatio(),
                request.protocol());
    }

    @Override
    @Transactional
    public void settle(
            ApiInferenceReservation reservation,
            ApiInferenceUsage usage,
            String finishReason) {
        AiModelApiUsage persisted = usageMapper.findByIdForUpdate(reservation.usageId());
        if (persisted == null
                || persisted.getBillingStatus() != AiModelBillingStatus.RESERVED.code()) {
            return;
        }
        long actualMinor = quotaCalculator.actualQuota(
                usage.inputTokens(),
                usage.cachedInputTokens(),
                usage.outputTokens(),
                reservation.inputRatio(),
                reservation.cachedInputRatio(),
                reservation.outputRatio());
        settleLocked(reservation, usage, finishReason, actualMinor, null);
    }

    @Override
    @Transactional
    public void settleCancellationEstimate(
            ApiInferenceReservation reservation,
            long emittedUtf8Bytes) {
        if (emittedUtf8Bytes <= 0) {
            AiModelApiUsage persisted = usageMapper.findByIdForUpdate(reservation.usageId());
            if (persisted != null
                    && persisted.getBillingStatus() == AiModelBillingStatus.RESERVED.code()) {
                refundLocked(reservation, "CLIENT_CANCELLED_NO_OUTPUT");
            }
            return;
        }
        AiModelApiUsage persisted = usageMapper.findByIdForUpdate(reservation.usageId());
        if (persisted == null
                || persisted.getBillingStatus() != AiModelBillingStatus.RESERVED.code()) {
            return;
        }
        long estimatedCompletion = Math.ceilDiv(emittedUtf8Bytes, 3L);
        ApiInferenceUsage usage = new ApiInferenceUsage(
                reservation.estimatedInputTokens(), estimatedCompletion, 0);
        long actualMinor = quotaCalculator.actualQuota(
                usage.inputTokens(),
                0,
                usage.outputTokens(),
                reservation.inputRatio(),
                reservation.cachedInputRatio(),
                reservation.outputRatio());
        settleLocked(
                reservation,
                usage,
                "CLIENT_CANCELLED_ESTIMATED",
                actualMinor,
                "CLIENT_CANCELLED_ESTIMATED");
    }

    @Override
    @Transactional
    public void refundSystemFailure(
            ApiInferenceReservation reservation,
            String failureCode) {
        AiModelApiUsage persisted = usageMapper.findByIdForUpdate(reservation.usageId());
        if (persisted == null
                || persisted.getBillingStatus() != AiModelBillingStatus.RESERVED.code()) {
            return;
        }
        refundLocked(reservation, failureCode);
    }

    private void refundLocked(
            ApiInferenceReservation reservation,
            String failureCode) {
        UserMembershipQuota quota = quotaMapper.findByLoginIdentityIdForUpdate(
                reservation.loginIdentityId());
        if (quota == null) {
            throw new IllegalStateException("API refund quota row is missing");
        }
        quota.setQuotaBalanceMinor(Math.addExact(
                quota.getQuotaBalanceMinor(), reservation.reservedMinor()));
        if (quotaMapper.updateBalanceAndPeriod(quota) != 1
                || usageMapper.settle(
                reservation.usageId(),
                AiModelBillingStatus.RESERVED.code(),
                AiModelBillingStatus.FAILED_REFUNDED.code(),
                null,
                null,
                null,
                0L,
                "SYSTEM_FAILURE_REFUNDED",
                safeFailureCode(failureCode),
                now()) != 1
                || detailMapper.finalizeDetail(
                reservation.usageId(), -reservation.reservedMinor()) != 1) {
            throw new IllegalStateException("API system failure refund did not fully apply");
        }
        cacheInvalidationExecutor.evictAfterCommit(reservation.loginIdentityId());
        count(reservation.protocol(), "refund", "failed_refunded");
    }

    private void settleLocked(
            ApiInferenceReservation reservation,
            ApiInferenceUsage usage,
            String finishReason,
            long actualMinor,
            String failureCode) {
        long delta = Math.subtractExact(actualMinor, reservation.reservedMinor());
        UserMembershipQuota quota = quotaMapper.findByLoginIdentityIdForUpdate(
                reservation.loginIdentityId());
        if (quota == null) {
            throw new IllegalStateException("API settlement quota row is missing");
        }
        int finalStatus = AiModelBillingStatus.SETTLED.code();
        long chargedMinor = actualMinor;
        if (delta < 0) {
            quota.setQuotaBalanceMinor(Math.addExact(quota.getQuotaBalanceMinor(), -delta));
        } else if (delta > 0 && quota.getQuotaBalanceMinor() >= delta) {
            quota.setQuotaBalanceMinor(Math.subtractExact(quota.getQuotaBalanceMinor(), delta));
        } else if (delta > 0) {
            // 余额不足时只保留已预扣金额并进入人工/后台对账，任何路径都不能把余额扣成负数。
            finalStatus = AiModelBillingStatus.RECONCILE_REQUIRED.code();
            chargedMinor = reservation.reservedMinor();
        }
        if (quotaMapper.updateBalanceAndPeriod(quota) != 1
                || usageMapper.settle(
                reservation.usageId(),
                AiModelBillingStatus.RESERVED.code(),
                finalStatus,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                chargedMinor,
                safeFinishReason(finishReason),
                failureCode,
                now()) != 1
                || detailMapper.finalizeDetail(reservation.usageId(), delta) != 1) {
            throw new IllegalStateException("API usage settlement did not fully apply");
        }
        cacheInvalidationExecutor.evictAfterCommit(reservation.loginIdentityId());
        count(reservation.protocol(), "settle",
                finalStatus == AiModelBillingStatus.RECONCILE_REQUIRED.code()
                ? "reconcile_required" : "settled");
    }

    private void validateAuthorization(
            ApiKeyReservationAuthorization authorization,
            ApiKeyPrincipal principal,
            ApiInferenceExecutionRequest request,
            OffsetDateTime now) {
        if (authorization == null
                || !Arrays.equals(authorization.getApiKeyId(), principal.apiKeyId())
                || !Objects.equals(
                authorization.getLoginIdentityId(), principal.loginIdentityId())
                || authorization.getKeyDigest() == null
                || !MessageDigest.isEqual(
                authorization.getKeyDigest(), principal.keyDigest())
                || !Objects.equals(authorization.getKeyStatus(), KEY_ENABLED)
                || (authorization.getExpiresAt() != null
                && !authorization.getExpiresAt().isAfter(now))) {
            throw new ApiChatException(
                    ApiChatErrorCode.INVALID_API_KEY,
                    "Invalid API Key.",
                    null);
        }
        if (!Objects.equals(authorization.getAccountStatus(), ACCOUNT_ACTIVE)) {
            throw new ApiChatException(
                    ApiChatErrorCode.ACCOUNT_NOT_ACTIVE,
                    "The account is not active.",
                    null);
        }
        if (!Objects.equals(authorization.getAiModelId(), request.model().id())
                || !Objects.equals(
                authorization.getModelName(), request.model().modelName())
                || !sameVendor(authorization.getVendor(), request.model().vendor())
                || !Boolean.TRUE.equals(authorization.getModelEnabled())
                || authorization.getInputRatio() == null
                || authorization.getCachedInputRatio() == null
                || authorization.getOutputRatio() == null
                || authorization.getContextWindowTokens() == null
                || authorization.getMaxOutputTokens() == null
                || authorization.getContextWindowTokens() <= 0
                || authorization.getMaxOutputTokens() <= 0
                || request.effectiveMaxOutputTokens()
                > authorization.getMaxOutputTokens()
                || request.estimatedInputTokens()
                > authorization.getContextWindowTokens()
                - request.effectiveMaxOutputTokens()) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_FOUND,
                    "The requested model is no longer available.",
                    "model");
        }
        if (!Objects.equals(authorization.getMappingStatus(), MAPPING_ACTIVE)) {
            throw new ApiChatException(
                    ApiChatErrorCode.MODEL_NOT_ALLOWED,
                    "The API Key is no longer authorized for this model.",
                    "model");
        }
    }

    private static boolean sameVendor(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private void activateExpiredPeriod(UserMembershipQuota quota, OffsetDateTime now) {
        if (quota.getQuotaPeriodEndsAt() != null
                && quota.getQuotaPeriodEndsAt().isAfter(now)) {
            return;
        }
        Integer code = quota.getMembershipTier();
        if (code == null || code < 0 || code >= MembershipTier.values().length) {
            throw new ApiChatException(
                    ApiChatErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "The account quota rule is unavailable.",
                    null);
        }
        MembershipQuotaPlan plan = quotaPlanService.getRequired(MembershipTier.values()[code]);
        quota.setQuotaBalanceMinor(plan.totalMinor());
        quota.setQuotaPeriodStartedAt(now);
        quota.setQuotaPeriodEndsAt(now.plus(plan.period()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private ApiChatException insufficient(ApiInferenceProtocol protocol) {
        count(protocol, "reserve", "insufficient_quota");
        return new ApiChatException(
                ApiChatErrorCode.INSUFFICIENT_QUOTA,
                "Insufficient quota to start this request.",
                null);
    }

    private static String safeFinishReason(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "UNKNOWN";
    }

    // Usage 与 Detail 必须各自获得非零 16 字节 ID，校验必须发生在扣费事务写库前。
    private byte[] nextHybridId() {
        byte[] id = idWorker.nextId();
        if (id == null || id.length != 16 || isZero(id)) {
            throw new IllegalStateException("Hybrid worker returned an invalid API usage ID");
        }
        return id.clone();
    }

    private static boolean isZero(byte[] value) {
        int aggregate = 0;
        for (byte current : value) {
            aggregate |= current;
        }
        return aggregate == 0;
    }

    private static String safeFailureCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}")
                ? value : "SYSTEM_FAILURE";
    }

    private void count(
            ApiInferenceProtocol protocol,
            String operation,
            String result) {
        meterRegistry.counter(
                "api.inference.billing",
                "protocol", protocol.name().toLowerCase(java.util.Locale.ROOT),
                "operation", operation,
                "result", result).increment();
        if (protocol != ApiInferenceProtocol.CHAT_COMPLETIONS) {
            return;
        }
        // 旧 Chat 指标继续只统计 Chat 请求，避免 Responses 上线改变现有告警基线。
        meterRegistry.counter(
                "api.chat.billing",
                "operation", operation,
                "result", result).increment();
    }
}
