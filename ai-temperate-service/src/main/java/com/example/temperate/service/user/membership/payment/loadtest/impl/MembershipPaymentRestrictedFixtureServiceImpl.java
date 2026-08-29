package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureState;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureUser;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 该实现是来为四个固定账号建立可跨应用重启恢复的 EDU/TEAM 测试夹具，并在同一数据库事务中保存或恢复完整额度事实。
 *
 * <p>原始会员行先写入 Git 忽略且仅文件所有者可读写的本地快照，再批量修改数据库；恢复快照只在恢复事务提交后删除，
 * 从而避免应用崩溃或事务回滚导致原始数据不可找回。该实现不创建表，也不允许客户端选择用户或目标套餐。</p>
 */
@Service
public final class MembershipPaymentRestrictedFixtureServiceImpl
        implements MembershipPaymentRestrictedFixtureService {

    private static final List<Long> FIXED_USER_IDS = List.of(
            84758509811535872L,
            84758866549673984L,
            84759380653903872L,
            84760794662834176L);
    private static final Map<Long, MembershipTier> TARGET_TIERS = Map.of(
            FIXED_USER_IDS.get(0), MembershipTier.EDU,
            FIXED_USER_IDS.get(1), MembershipTier.EDU,
            FIXED_USER_IDS.get(2), MembershipTier.TEAM,
            FIXED_USER_IDS.get(3), MembershipTier.TEAM);

    private final MembershipPaymentLoadtestProperties properties;
    private final UserMembershipQuotaMapper quotaMapper;
    private final MembershipQuotaPlanService planService;
    private final UserProfileCacheInvalidationExecutor invalidationExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path snapshotPath;

    public MembershipPaymentRestrictedFixtureServiceImpl(
            MembershipPaymentLoadtestProperties properties,
            UserMembershipQuotaMapper quotaMapper,
            MembershipQuotaPlanService planService,
            UserProfileCacheInvalidationExecutor invalidationExecutor,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.membership-payment.loadtest.restricted-fixture-snapshot-path:loadtest/local/restricted-membership-fixtures.json}")
                    String snapshotPath) {
        this.properties = Objects.requireNonNull(properties);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.planService = Objects.requireNonNull(planService);
        this.invalidationExecutor = Objects.requireNonNull(invalidationExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.snapshotPath = Path.of(Objects.requireNonNull(snapshotPath))
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 固定顺序锁定四条额度行并先保存完整原值，再一次批量切换受限套餐；影响行数不完整时事务必须回滚。
     */
    @Override
    @Transactional
    public MembershipPaymentRestrictedFixtureState prepare() {
        requireEnabledAndAllowlisted();
        if (Files.exists(snapshotPath)) {
            MembershipPaymentRestrictedFixtureState current = state();
            if (current.prepared()) {
                return current;
            }
            throw new IllegalStateException(
                    "Restricted membership fixture snapshot exists but current rows are not prepared.");
        }

        List<UserMembershipQuota> originals = requireFourRows(
                quotaMapper.findByLoginIdentityIdsForUpdate(FIXED_USER_IDS));
        writeSnapshot(new Snapshot(1, MembershipPaymentTime.now(clock), originals.stream()
                .map(SnapshotUser::from)
                .toList()));

        OffsetDateTime now = MembershipPaymentTime.now(clock);
        List<GrantRow> grants = new ArrayList<>(FIXED_USER_IDS.size());
        for (long userId : FIXED_USER_IDS) {
            MembershipTier tier = TARGET_TIERS.get(userId);
            MembershipQuotaPlan plan = planService.getRequired(tier);
            grants.add(new GrantRow(
                    userId,
                    tier.ordinal(),
                    plan.totalMinor(),
                    null,
                    now,
                    now.plusMonths(1)));
        }
        requireFourUpdates(grants);
        invalidationExecutor.evictAfterCommit(FIXED_USER_IDS);
        return fixtureState(true, true, grants.stream()
                .map(grant -> new MembershipPaymentRestrictedFixtureUser(
                        grant.loginIdentityId(),
                        MembershipTier.values()[grant.membershipTier()].name()))
                .toList());
    }

    @Override
    public MembershipPaymentRestrictedFixtureState state() {
        requireEnabledAndAllowlisted();
        List<UserMembershipQuota> rows = quotaMapper.findByLoginIdentityIds(FIXED_USER_IDS);
        Map<Long, UserMembershipQuota> byId = index(rows);
        List<MembershipPaymentRestrictedFixtureUser> users = new ArrayList<>();
        boolean prepared = rows.size() == FIXED_USER_IDS.size();
        for (long userId : FIXED_USER_IDS) {
            UserMembershipQuota row = byId.get(userId);
            String tier = tierName(row);
            users.add(new MembershipPaymentRestrictedFixtureUser(userId, tier));
            prepared &= row != null
                    && row.getMembershipTier() != null
                    && row.getMembershipTier() == TARGET_TIERS.get(userId).ordinal();
        }
        return fixtureState(prepared, Files.exists(snapshotPath), users);
    }

    /**
     * 恢复时重新锁定四条当前行并批量写回快照；快照删除挂在提交回调上，数据库回滚时仍保留恢复依据。
     */
    @Override
    @Transactional
    public MembershipPaymentRestrictedFixtureState restore() {
        requireEnabledAndAllowlisted();
        if (!Files.exists(snapshotPath)) {
            MembershipPaymentRestrictedFixtureState current = state();
            return fixtureState(false, false, current.users());
        }
        Snapshot snapshot = readSnapshot();
        if (snapshot.schemaVersion() != 1 || snapshot.users().size() != FIXED_USER_IDS.size()
                || !snapshot.users().stream()
                        .map(SnapshotUser::loginIdentityId)
                        .toList()
                        .equals(FIXED_USER_IDS)) {
            throw new IllegalStateException("Restricted membership fixture snapshot is invalid.");
        }
        requireFourRows(quotaMapper.findByLoginIdentityIdsForUpdate(FIXED_USER_IDS));
        List<GrantRow> originals = snapshot.users().stream()
                .map(SnapshotUser::toGrant)
                .toList();
        requireFourUpdates(originals);
        invalidationExecutor.evictAfterCommit(FIXED_USER_IDS);
        deleteSnapshotAfterCommit();
        return fixtureState(false, true, snapshot.users().stream()
                .map(user -> new MembershipPaymentRestrictedFixtureUser(
                        user.loginIdentityId(), tierName(user.membershipTier())))
                .toList());
    }

    private void requireEnabledAndAllowlisted() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }
        if (!properties.allowedUserIds().containsAll(FIXED_USER_IDS)) {
            throw new IllegalStateException(
                    "Restricted membership fixture users are not fully allowlisted.");
        }
    }

    private List<UserMembershipQuota> requireFourRows(List<UserMembershipQuota> rows) {
        List<UserMembershipQuota> valid = rows == null ? List.of() : rows;
        if (valid.size() != FIXED_USER_IDS.size()
                || !valid.stream()
                        .map(UserMembershipQuota::getLoginIdentityId)
                        .toList()
                        .equals(FIXED_USER_IDS)) {
            throw new IllegalStateException(
                    "Restricted membership fixture requires exactly four ordered quota rows.");
        }
        return valid;
    }

    private void requireFourUpdates(List<GrantRow> grants) {
        try {
            if (quotaMapper.batchGrantPaidMemberships(
                    objectMapper.writeValueAsString(grants)) != FIXED_USER_IDS.size()) {
                throw new IllegalStateException(
                        "Restricted membership fixture update affected an unexpected row count.");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Restricted membership fixture serialization failed.", exception);
        }
    }

    private void writeSnapshot(Snapshot snapshot) {
        Path temporary = snapshotPath.resolveSibling(
                snapshotPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(snapshotPath.getParent());
            Files.write(temporary, objectMapper.writeValueAsBytes(snapshot));
            restrictToOwner(temporary);
            try {
                Files.move(temporary, snapshotPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, snapshotPath);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 原始异常决定失败结果；临时文件清理失败不允许覆盖其原因。
            }
            throw new IllegalStateException(
                    "Restricted membership fixture snapshot could not be written.", exception);
        }
    }

    private Snapshot readSnapshot() {
        try {
            return objectMapper.readValue(snapshotPath.toFile(), Snapshot.class);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Restricted membership fixture snapshot could not be read.", exception);
        }
    }

    private void deleteSnapshotAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Restricted membership fixture restore requires transaction synchronization.");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Files.deleteIfExists(snapshotPath);
                        } catch (IOException exception) {
                            throw new IllegalStateException(
                                    "Restricted membership fixture snapshot cleanup failed.", exception);
                        }
                    }
                });
    }

    private static void restrictToOwner(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    path,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        } catch (UnsupportedOperationException ignored) {
            // Windows 使用 ACL 视图继续收紧；不支持 POSIX 权限不是可忽略的最终状态。
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("File system does not expose an owner-only permission model.");
        }
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerOnly));
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

    private static String tierName(UserMembershipQuota row) {
        return row == null ? "MISSING" : tierName(row.getMembershipTier());
    }

    private static String tierName(Integer ordinal) {
        return ordinal == null || ordinal < 0 || ordinal >= MembershipTier.values().length
                ? "UNKNOWN"
                : MembershipTier.values()[ordinal].name();
    }

    private static MembershipPaymentRestrictedFixtureState fixtureState(
            boolean prepared,
            boolean snapshotPresent,
            List<MembershipPaymentRestrictedFixtureUser> users) {
        return new MembershipPaymentRestrictedFixtureState(
                prepared, snapshotPresent, users);
    }

    private record Snapshot(int schemaVersion, OffsetDateTime savedAt, List<SnapshotUser> users) {
        private Snapshot {
            users = users == null ? List.of() : List.copyOf(users);
        }
    }

    private record SnapshotUser(
            long loginIdentityId,
            int membershipTier,
            long quotaBalanceMinor,
            OffsetDateTime quotaPeriodStartedAt,
            OffsetDateTime quotaPeriodEndsAt,
            OffsetDateTime membershipExpiresAt) {

        private static SnapshotUser from(UserMembershipQuota quota) {
            return new SnapshotUser(
                    quota.getLoginIdentityId(),
                    quota.getMembershipTier(),
                    quota.getQuotaBalanceMinor(),
                    quota.getQuotaPeriodStartedAt(),
                    quota.getQuotaPeriodEndsAt(),
                    quota.getMembershipExpiresAt());
        }

        private GrantRow toGrant() {
            return new GrantRow(
                    loginIdentityId,
                    membershipTier,
                    quotaBalanceMinor,
                    quotaPeriodStartedAt,
                    quotaPeriodEndsAt,
                    membershipExpiresAt);
        }
    }

    private record GrantRow(
            long loginIdentityId,
            int membershipTier,
            long quotaBalanceMinor,
            OffsetDateTime quotaPeriodStartedAt,
            OffsetDateTime quotaPeriodEndsAt,
            OffsetDateTime membershipExpiresAt) {
    }
}
