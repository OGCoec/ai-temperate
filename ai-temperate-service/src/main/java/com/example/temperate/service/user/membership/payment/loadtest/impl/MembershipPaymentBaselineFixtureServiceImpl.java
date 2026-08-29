package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureState;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureUser;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来在本地正式浸泡开始前锁定并批量重置十六个批准账号，消除上一轮权益发放对下一轮的污染。
 *
 * <p>重置只在 loadtest 开关启用且白名单与固定账号全集完全一致时执行；所有额度行在同一事务内按固定顺序锁定，
 * 统一写为 FREE 满额、周期尚未激活和无会员到期时间，并在提交后批量删除用户资料缓存。</p>
 */
@Service
public final class MembershipPaymentBaselineFixtureServiceImpl
        implements MembershipPaymentBaselineFixtureService {

    private static final List<Long> FIXED_USER_IDS = List.of(
            72659006262480896L,
            73014701344296960L,
            74891801495998464L,
            76721355290185728L,
            84736921162616832L,
            84739559597936640L,
            84742296792338432L,
            84745417706835968L,
            84746552547086336L,
            84753114204344320L,
            84754367089086464L,
            84755204414771200L,
            84758509811535872L,
            84758866549673984L,
            84759380653903872L,
            84760794662834176L);

    private final MembershipPaymentLoadtestProperties properties;
    private final UserMembershipQuotaMapper quotaMapper;
    private final MembershipQuotaPlanService planService;
    private final UserProfileCacheInvalidationExecutor invalidationExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MembershipPaymentBaselineFixtureServiceImpl(
            MembershipPaymentLoadtestProperties properties,
            UserMembershipQuotaMapper quotaMapper,
            MembershipQuotaPlanService planService,
            UserProfileCacheInvalidationExecutor invalidationExecutor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.planService = Objects.requireNonNull(planService);
        this.invalidationExecutor = Objects.requireNonNull(invalidationExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 固定顺序锁定十六条额度行后一次批量覆盖全部基线字段；任何缺行或影响行数不完整都会回滚整个事务。
     */
    @Override
    @Transactional
    public MembershipPaymentBaselineFixtureState prepare() {
        requireEnabledAndExactAllowlist();
        requireSixteenRows(quotaMapper.findByLoginIdentityIdsForUpdate(FIXED_USER_IDS));
        MembershipQuotaPlan freePlan = planService.getRequired(MembershipTier.FREE);
        OffsetDateTime now = MembershipPaymentTime.now(clock);
        List<GrantRow> grants = FIXED_USER_IDS.stream()
                .map(userId -> new GrantRow(
                        userId,
                        MembershipTier.FREE.ordinal(),
                        freePlan.totalMinor(),
                        null,
                        now,
                        null))
                .toList();
        requireSixteenUpdates(grants);
        invalidationExecutor.evictAfterCommit(FIXED_USER_IDS);
        return new MembershipPaymentBaselineFixtureState(
                true,
                FIXED_USER_IDS.stream()
                        .map(userId -> new MembershipPaymentBaselineFixtureUser(
                                userId, MembershipTier.FREE.name()))
                        .toList());
    }

    @Override
    public MembershipPaymentBaselineFixtureState state() {
        requireEnabledAndExactAllowlist();
        List<UserMembershipQuota> rows = quotaMapper.findByLoginIdentityIds(FIXED_USER_IDS);
        Map<Long, UserMembershipQuota> indexed = index(rows);
        MembershipQuotaPlan freePlan = planService.getRequired(MembershipTier.FREE);
        List<MembershipPaymentBaselineFixtureUser> users = new ArrayList<>();
        boolean prepared = rows != null && rows.size() == FIXED_USER_IDS.size();
        for (long userId : FIXED_USER_IDS) {
            UserMembershipQuota row = indexed.get(userId);
            users.add(new MembershipPaymentBaselineFixtureUser(userId, tierName(row)));
            prepared &= isPrepared(row, freePlan.totalMinor());
        }
        return new MembershipPaymentBaselineFixtureState(prepared, users);
    }

    private void requireEnabledAndExactAllowlist() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }
        if (!new HashSet<>(properties.allowedUserIds())
                .equals(new HashSet<>(FIXED_USER_IDS))) {
            throw new IllegalStateException(
                    "Membership payment baseline fixture requires the exact approved allowlist.");
        }
    }

    private static void requireSixteenRows(List<UserMembershipQuota> rows) {
        List<UserMembershipQuota> valid = rows == null ? List.of() : rows;
        if (valid.size() != FIXED_USER_IDS.size()
                || !valid.stream()
                        .map(UserMembershipQuota::getLoginIdentityId)
                        .toList()
                        .equals(FIXED_USER_IDS)) {
            throw new IllegalStateException(
                    "Membership payment baseline fixture requires exactly sixteen ordered quota rows.");
        }
    }

    private void requireSixteenUpdates(List<GrantRow> grants) {
        try {
            if (quotaMapper.batchGrantPaidMemberships(
                    objectMapper.writeValueAsString(grants)) != FIXED_USER_IDS.size()) {
                throw new IllegalStateException(
                        "Membership payment baseline fixture update affected an unexpected row count.");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Membership payment baseline fixture serialization failed.", exception);
        }
    }

    private static Map<Long, UserMembershipQuota> index(List<UserMembershipQuota> rows) {
        Map<Long, UserMembershipQuota> indexed = new LinkedHashMap<>();
        if (rows != null) {
            for (UserMembershipQuota row : rows) {
                if (row != null && row.getLoginIdentityId() != null) {
                    indexed.put(row.getLoginIdentityId(), row);
                }
            }
        }
        return indexed;
    }

    private static boolean isPrepared(UserMembershipQuota row, long freeTotalMinor) {
        return row != null
                && row.getMembershipTier() != null
                && row.getMembershipTier() == MembershipTier.FREE.ordinal()
                && row.getQuotaBalanceMinor() == freeTotalMinor
                && row.getQuotaPeriodStartedAt() == null
                && row.getQuotaPeriodEndsAt() != null
                && row.getMembershipExpiresAt() == null;
    }

    private static String tierName(UserMembershipQuota row) {
        if (row == null || row.getMembershipTier() == null
                || row.getMembershipTier() < 0
                || row.getMembershipTier() >= MembershipTier.values().length) {
            return row == null ? "MISSING" : "UNKNOWN";
        }
        return MembershipTier.values()[row.getMembershipTier()].name();
    }

    /** 该 JSON 行复用既有批量权益写入协议，但只用于固定压测账号的本地基线。 */
    private record GrantRow(
            long loginIdentityId,
            int membershipTier,
            long quotaBalanceMinor,
            OffsetDateTime quotaPeriodStartedAt,
            OffsetDateTime quotaPeriodEndsAt,
            OffsetDateTime membershipExpiresAt) {
    }
}
