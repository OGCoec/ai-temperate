package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.entity.UserProfile;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentBoundaryLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryFixtureState;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryLoadtestPolicy;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来事务化创建并长期保留四万个固定测试账号，同时在每轮测试前精确清理本轮订单并恢复 FREE 基线。
 *
 * <p>模板创建只允许发生在整个固定区间完全为空时；已有模板必须逐字段匹配固定拓扑。清理先锁定并验证全部订单均属于
 * 固定用户且已完成权益裁决，再删除关联回调和精确订单，最后分八十批恢复额度并在事务提交后删除资料缓存。</p>
 */
@Service
public final class MembershipPaymentBoundaryFixtureServiceImpl
        implements MembershipPaymentBoundaryFixtureService {

    private static final int PAGE_SIZE = 500;
    private static final int MAX_RUN_ORDERS = 40_000;
    private static final int RESET_BATCH_SIZE = 2_000;

    private final MembershipPaymentBoundaryLoadtestProperties properties;
    private final MembershipPaymentBoundaryLoadtestPolicy policy;
    private final UserLoginIdentityMapper identityMapper;
    private final UserProfileMapper profileMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final MembershipOrderMapper orderMapper;
    private final MembershipPaymentCallbackMapper callbackMapper;
    private final MembershipQuotaPlanService planService;
    private final UserProfileCacheInvalidationExecutor invalidationExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MembershipPaymentBoundaryFixtureServiceImpl(
            MembershipPaymentBoundaryLoadtestProperties properties,
            MembershipPaymentBoundaryLoadtestPolicy policy,
            UserLoginIdentityMapper identityMapper,
            UserProfileMapper profileMapper,
            UserMembershipQuotaMapper quotaMapper,
            MembershipOrderMapper orderMapper,
            MembershipPaymentCallbackMapper callbackMapper,
            MembershipQuotaPlanService planService,
            UserProfileCacheInvalidationExecutor invalidationExecutor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.policy = Objects.requireNonNull(policy);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.profileMapper = Objects.requireNonNull(profileMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.callbackMapper = Objects.requireNonNull(callbackMapper);
        this.planService = Objects.requireNonNull(planService);
        this.invalidationExecutor = Objects.requireNonNull(invalidationExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 在一个事务中创建或验证固定模板；任何批次影响行数不完整都会抛错并回滚此前全部批次。
     */
    @Override
    @Transactional
    public MembershipPaymentBoundaryFixtureState prepare() {
        requireEnabled();
        TemplateRows rows = loadTemplateRows();
        int orders = countOrders();
        int callbacks = countCallbacks();
        if (rows.totalRows() == 0) {
            requireNoPaymentResidue(orders, callbacks);
            createTemplates();
        } else {
            validatePersistentTemplates(rows);
            requireNoPaymentResidue(orders, callbacks);
            resetQuotas();
        }
        evictAllPagesAfterCommit();
        return preparedState();
    }

    /** 只读取非敏感计数并逐页验证模板；该方法不会修改模板或清理支付数据。 */
    @Override
    @Transactional(readOnly = true)
    public MembershipPaymentBoundaryFixtureState state() {
        requireEnabled();
        TemplateRows rows = loadTemplateRows();
        int orders = countOrders();
        int callbacks = countCallbacks();
        long freeQuota = freeQuota();
        boolean prepared = isPersistentTemplate(rows)
                && rows.quotas().stream().allMatch(row -> isFreeBaseline(row, freeQuota))
                && orders == 0
                && callbacks == 0;
        return new MembershipPaymentBoundaryFixtureState(
                prepared,
                rows.identities().size(),
                rows.profiles().size(),
                rows.quotas().size(),
                orders,
                callbacks);
    }

    /**
     * 只删除完整本轮清单关联的支付数据；未知、遗漏、活动或权益未决订单都会使事务在任何删除发生前失败。
     */
    @Override
    @Transactional
    public MembershipPaymentBoundaryFixtureState reset(List<byte[]> runOrderIds) {
        requireEnabled();
        List<byte[]> exactIds = validateOrderIdInput(runOrderIds);
        int existingOrders = countOrders();
        if (existingOrders != exactIds.size()) {
            throw new IllegalStateException(
                    "Boundary fixture reset requires the complete run-owned order manifest.");
        }
        // 四万订单必须在同一事务内完整校验后清理，但每条 SQL 最多处理两千个 ID，
        // 避免单条 JSON 参数、行锁集合和执行计划无界膨胀；任一批失败会回滚此前批次。
        for (int offset = 0; offset < exactIds.size(); offset += RESET_BATCH_SIZE) {
            List<byte[]> batch = exactIds.subList(
                    offset, Math.min(offset + RESET_BATCH_SIZE, exactIds.size()));
            String idsJson = encodeOrderIds(batch);
            List<MembershipOrder> lockedOrders = orderMapper.findByIdsJsonForUpdate(idsJson);
            validateTerminalResolvedOrders(lockedOrders, batch);
            callbackMapper.deleteByOrderIdsJson(idsJson);
            if (orderMapper.deleteByIdsJson(idsJson) != batch.size()) {
                throw new IllegalStateException(
                        "Boundary fixture reset deleted an unexpected order count.");
            }
        }
        validatePersistentTemplates(loadTemplateRows());
        resetQuotas();
        evictAllPagesAfterCommit();
        return preparedState();
    }

    private void createTemplates() {
        OffsetDateTime now = now();
        long freeQuota = freeQuota();
        for (int page = 0; page < policy.totalPages(); page++) {
            List<Long> ids = policy.pageUserIds(page);
            requireBatchCount(
                    identityMapper.batchInsertBoundaryFixtures(createIdentities(ids)),
                    "identity insert");
            requireBatchCount(
                    profileMapper.batchInsertBoundaryFixtures(createProfiles(ids)),
                    "profile insert");
            requireBatchCount(
                    quotaMapper.batchInsertBoundaryFixtures(createQuotas(ids, freeQuota, now)),
                    "quota insert");
        }
    }

    private void resetQuotas() {
        OffsetDateTime now = now();
        long freeQuota = freeQuota();
        for (int page = 0; page < policy.totalPages(); page++) {
            List<GrantRow> grants = policy.pageUserIds(page).stream()
                    .map(userId -> new GrantRow(
                            userId,
                            MembershipTier.FREE.ordinal(),
                            freeQuota,
                            null,
                            now,
                            null))
                    .toList();
            try {
                requireBatchCount(
                        quotaMapper.batchGrantPaidMemberships(objectMapper.writeValueAsString(grants)),
                        "quota reset");
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Boundary fixture quota serialization failed.", exception);
            }
        }
    }

    private TemplateRows loadTemplateRows() {
        List<UserLoginIdentity> identities = new ArrayList<>(policy.totalUsers());
        List<UserProfile> profiles = new ArrayList<>(policy.totalUsers());
        List<UserMembershipQuota> quotas = new ArrayList<>(policy.totalUsers());
        for (int page = 0; page < policy.totalPages(); page++) {
            List<Long> ids = policy.pageUserIds(page);
            identities.addAll(safe(identityMapper.findByIds(ids)));
            profiles.addAll(safe(profileMapper.findByLoginIdentityIds(ids)));
            quotas.addAll(safe(quotaMapper.findByLoginIdentityIds(ids)));
        }
        return new TemplateRows(identities, profiles, quotas);
    }

    private void validatePersistentTemplates(TemplateRows rows) {
        if (!isPersistentTemplate(rows)) {
            throw new IllegalStateException(
                    "Boundary fixture template is partial, foreign, or does not match the fixed policy.");
        }
    }

    private boolean isPersistentTemplate(TemplateRows rows) {
        if (rows.identities().size() != policy.totalUsers()
                || rows.profiles().size() != policy.totalUsers()
                || rows.quotas().size() != policy.totalUsers()) {
            return false;
        }
        for (int index = 0; index < policy.totalUsers(); index++) {
            long expectedId = policy.firstUserId() + index;
            if (!matchesIdentity(rows.identities().get(index), expectedId, index)
                    || !matchesProfile(rows.profiles().get(index), expectedId, index)
                    || !Objects.equals(rows.quotas().get(index).getLoginIdentityId(), expectedId)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesIdentity(UserLoginIdentity row, long expectedId, int index) {
        return row != null
                && Objects.equals(row.getId(), expectedId)
                && row.getRegistrationSource() == RegistrationSource.STANDARD
                && Objects.equals(
                        row.getEmail(),
                        "membership-boundary-%04d@example.invalid".formatted(index))
                && Boolean.FALSE.equals(row.getEmailVerified())
                && row.getGithubSubject() == null
                && row.getGoogleSubject() == null
                && row.getPhone() == null
                && row.getPasswordHash() == null;
    }

    private static boolean matchesProfile(UserProfile row, long expectedId, int index) {
        return row != null
                && Objects.equals(row.getLoginIdentityId(), expectedId)
                && Objects.equals(row.getDisplayName(), "Membership Boundary %04d".formatted(index))
                && row.getAccountStatus() != null
                && row.getAccountStatus() == 0
                && row.getAvatarUrl() == null;
    }

    private static boolean isFreeBaseline(UserMembershipQuota row, long freeQuota) {
        return row != null
                && row.getMembershipTier() != null
                && row.getMembershipTier() == MembershipTier.FREE.ordinal()
                && row.getQuotaBalanceMinor() == freeQuota
                && row.getQuotaPeriodStartedAt() == null
                && row.getQuotaPeriodEndsAt() != null
                && row.getMembershipExpiresAt() == null;
    }

    private void validateTerminalResolvedOrders(
            List<MembershipOrder> orders,
            List<byte[]> exactIds) {
        List<MembershipOrder> safeOrders = safe(orders);
        Set<String> expected = exactIds.stream().map(HexFormat.of()::formatHex).collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actual = safeOrders.stream()
                .map(MembershipOrder::getId)
                .filter(Objects::nonNull)
                .map(HexFormat.of()::formatHex)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean valid = expected.equals(actual) && safeOrders.size() == exactIds.size();
        for (MembershipOrder order : safeOrders) {
            valid &= order != null
                    && order.getLoginIdentityId() != null
                    && policy.isBoundaryUser(order.getLoginIdentityId())
                    && order.getStatus() != null
                    && order.getStatus().terminal()
                    && order.getEntitlementResolution() != null;
        }
        if (!valid) {
            throw new IllegalStateException(
                    "Boundary fixture reset requires only terminal and entitlement-resolved run orders.");
        }
    }

    private List<byte[]> validateOrderIdInput(List<byte[]> runOrderIds) {
        List<byte[]> ids = runOrderIds == null ? List.of() : List.copyOf(runOrderIds);
        if (ids.size() > MAX_RUN_ORDERS) {
            throw new IllegalArgumentException("Boundary fixture order manifest exceeds 40,000 entries.");
        }
        Set<String> unique = new HashSet<>();
        for (byte[] id : ids) {
            if (id == null || id.length != 16 || !unique.add(HexFormat.of().formatHex(id))) {
                throw new IllegalArgumentException(
                        "Boundary fixture order manifest contains an invalid or duplicate ID.");
            }
        }
        return ids;
    }

    private String encodeOrderIds(List<byte[]> ids) {
        try {
            return objectMapper.writeValueAsString(
                    ids.stream().map(HexFormat.of()::formatHex).toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Boundary fixture order manifest serialization failed.", exception);
        }
    }

    private List<UserLoginIdentity> createIdentities(List<Long> ids) {
        return ids.stream().map(userId -> {
            int index = Math.toIntExact(userId - policy.firstUserId());
            UserLoginIdentity row = new UserLoginIdentity();
            row.setId(userId);
            row.setRegistrationSource(RegistrationSource.STANDARD);
            row.setEmail("membership-boundary-%04d@example.invalid".formatted(index));
            row.setEmailVerified(false);
            return row;
        }).toList();
    }

    private List<UserProfile> createProfiles(List<Long> ids) {
        return ids.stream().map(userId -> {
            int index = Math.toIntExact(userId - policy.firstUserId());
            UserProfile row = new UserProfile();
            row.setLoginIdentityId(userId);
            row.setDisplayName("Membership Boundary %04d".formatted(index));
            row.setAccountStatus(0);
            return row;
        }).toList();
    }

    private static List<UserMembershipQuota> createQuotas(
            List<Long> ids,
            long freeQuota,
            OffsetDateTime now) {
        return ids.stream().map(userId -> {
            UserMembershipQuota row = new UserMembershipQuota();
            row.setLoginIdentityId(userId);
            row.setMembershipTier(MembershipTier.FREE.ordinal());
            row.setQuotaBalanceMinor(freeQuota);
            row.setQuotaPeriodEndsAt(now);
            return row;
        }).toList();
    }

    private MembershipPaymentBoundaryFixtureState preparedState() {
        int orders = countOrders();
        int callbacks = countCallbacks();
        return new MembershipPaymentBoundaryFixtureState(
                orders == 0 && callbacks == 0,
                policy.totalUsers(),
                policy.totalUsers(),
                policy.totalUsers(),
                orders,
                callbacks);
    }

    private int countOrders() {
        return orderMapper.countByLoginIdentityIdRange(
                policy.firstUserId(), policy.lastUserId() + 1L);
    }

    private int countCallbacks() {
        return callbackMapper.countByLoginIdentityIdRange(
                policy.firstUserId(), policy.lastUserId() + 1L);
    }

    private void requireNoPaymentResidue(int orders, int callbacks) {
        if (orders != 0 || callbacks != 0) {
            throw new IllegalStateException(
                    "Boundary fixture contains payment residue and requires an exact reset manifest.");
        }
    }

    private static void requireBatchCount(int affectedRows, String operation) {
        if (affectedRows != PAGE_SIZE) {
            throw new IllegalStateException(
                    "Boundary fixture " + operation + " affected an unexpected row count.");
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment boundary loadtest is disabled.");
        }
    }

    private long freeQuota() {
        MembershipQuotaPlan freePlan = planService.getRequired(MembershipTier.FREE);
        return freePlan.totalMinor();
    }

    private OffsetDateTime now() {
        return MembershipPaymentTime.now(clock);
    }

    private void evictAllPagesAfterCommit() {
        // 用户资料缓存执行器单批最多接受五百个 ID；逐页注册八十个提交后动作，既不扩大
        // 单次 Redis 请求，也保证数据库事务回滚时不会提前删除任何缓存。
        for (int page = 0; page < policy.totalPages(); page++) {
            invalidationExecutor.evictAfterCommit(policy.pageUserIds(page));
        }
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private record TemplateRows(
            List<UserLoginIdentity> identities,
            List<UserProfile> profiles,
            List<UserMembershipQuota> quotas) {

        private int totalRows() {
            return identities.size() + profiles.size() + quotas.size();
        }
    }

    /** 该 JSON 行复用正式批量权益写入协议，但只作用于固定四万测试用户。 */
    private record GrantRow(
            long loginIdentityId,
            int membershipTier,
            long quotaBalanceMinor,
            OffsetDateTime quotaPeriodStartedAt,
            OffsetDateTime quotaPeriodEndsAt,
            OffsetDateTime membershipExpiresAt) {
    }
}
