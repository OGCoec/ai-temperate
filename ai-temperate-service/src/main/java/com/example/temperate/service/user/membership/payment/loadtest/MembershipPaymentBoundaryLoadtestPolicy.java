package com.example.temperate.service.user.membership.payment.loadtest;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * 定义八万账号夹具以及 40K 性能、80K 容量两种不可配置的时间分组、套餐分布和 TEAM 负向探针。
 *
 * <p>该策略只表达固定测试拓扑，不读取 HTTP 参数或环境配置，避免受控测试接口被扩大成任意用户批量操作能力。</p>
 */
public final class MembershipPaymentBoundaryLoadtestPolicy {

    private static final long FIRST_USER_ID = 70_000_000_000_000_000L;
    private static final int TOTAL_USERS = 80_000;
    private static final int PAGE_SIZE = 500;
    private static final int TOTAL_PAGES = TOTAL_USERS / PAGE_SIZE;
    private static final int TOTAL_GROUPS = 8;
    private static final int OFFSET_CYCLE_SIZE = 500;
    private static final int TEAM_PROBES_PER_GROUP = 25;
    private static final List<MembershipTier> PERSONAL_TARGET_TIERS =
            List.of(MembershipTier.GO, MembershipTier.PLUS, MembershipTier.PRO, MembershipTier.MAX);
    private static final List<String> GROUP_CODES =
            List.of("E-P1", "E-PR", "E-A1", "E-AR", "H-P1", "H-PR", "H-A1", "H-AR");
    private static final List<BoundaryReference> GROUP_REFERENCES = List.of(
            BoundaryReference.EXPIRES_AT, BoundaryReference.EXPIRES_AT,
            BoundaryReference.EXPIRES_AT, BoundaryReference.EXPIRES_AT,
            BoundaryReference.HARD_CLOSE_AT, BoundaryReference.HARD_CLOSE_AT,
            BoundaryReference.HARD_CLOSE_AT, BoundaryReference.HARD_CLOSE_AT);
    private static final List<Long> GROUP_FIRST_OFFSETS =
            List.of(-1L, -1_000L, 1L, 0L, -1L, -1_000L, 1L, 0L);
    private static final List<Long> GROUP_OFFSET_STEPS =
            List.of(0L, 2L, 0L, 2L, 0L, 2L, 0L, 2L);

    public long firstUserId() {
        return FIRST_USER_ID;
    }

    public long lastUserId() {
        return FIRST_USER_ID + TOTAL_USERS - 1L;
    }

    public int totalUsers() {
        return TOTAL_USERS;
    }

    /** 返回固定五百用户分页的总页数，供夹具和 Token 签发共享同一个范围上限。 */
    public int totalPages() {
        return TOTAL_PAGES;
    }

    public boolean isBoundaryUser(long userId) {
        return userId >= FIRST_USER_ID && userId <= lastUserId();
    }

    /**
     * 返回固定的五百用户 Token 分页；页码不合法时拒绝，禁止调用方扩大签发范围。
     *
     * @param page 固定页码，范围为 0 到 159
     * @return 不可变用户 ID 列表
     */
    public List<Long> pageUserIds(int page) {
        requireIndex(page, TOTAL_PAGES, "Token 页码");
        long pageFirstUserId = FIRST_USER_ID + (long) page * PAGE_SIZE;
        return LongStream.range(pageFirstUserId, pageFirstUserId + PAGE_SIZE).boxed().toList();
    }

    public List<BoundaryGroup> groups(RunScale scale) {
        RunScale requiredScale = java.util.Objects.requireNonNull(scale, "运行规模不能为空");
        return IntStream.range(0, TOTAL_GROUPS)
                .mapToObj(index -> group(requiredScale, index))
                .toList();
    }

    /**
     * 按所选固定规模的四个连续等分区确定个人套餐，保证每个区段获得完全相同的套餐分布。
     *
     * @param userId 固定模板用户 ID
     * @return GO、PLUS、PRO 或 MAX
     */
    public MembershipTier targetTier(RunScale scale, long userId) {
        RunScale requiredScale = requireScaleUser(scale, userId);
        int offsetInsideGroup = (int) ((userId - FIRST_USER_ID) % requiredScale.groupSize());
        return PERSONAL_TARGET_TIERS.get(offsetInsideGroup / requiredScale.usersPerTier());
    }

    /**
     * 返回所选固定规模每个区段最前面的 25 个用户，用于先验证 TEAM 被拒绝且不产生订单。
     *
     * @param groupIndex 固定区段索引，范围为 0 到 7
     * @return 不可变的 25 用户列表
     */
    public List<Long> teamProbeUserIds(RunScale scale, int groupIndex) {
        RunScale requiredScale = java.util.Objects.requireNonNull(scale, "运行规模不能为空");
        requireIndex(groupIndex, TOTAL_GROUPS, "区段索引");
        long groupFirstUserId = FIRST_USER_ID + (long) groupIndex * requiredScale.groupSize();
        return LongStream.range(groupFirstUserId, groupFirstUserId + TEAM_PROBES_PER_GROUP)
                .boxed()
                .toList();
    }

    private void requireBoundaryUser(long userId) {
        if (!isBoundaryUser(userId)) {
            throw new IllegalArgumentException("用户不属于固定毫秒边界测试区间");
        }
    }

    private RunScale requireScaleUser(RunScale scale, long userId) {
        RunScale requiredScale = java.util.Objects.requireNonNull(scale, "运行规模不能为空");
        requireBoundaryUser(userId);
        if (userId >= FIRST_USER_ID + requiredScale.totalRunUsers()) {
            throw new IllegalArgumentException("用户不属于所选固定运行规模");
        }
        return requiredScale;
    }

    private static void requireIndex(int index, int upperExclusive, String label) {
        if (index < 0 || index >= upperExclusive) {
            throw new IllegalArgumentException(label + "超出固定范围");
        }
    }

    private static BoundaryGroup group(RunScale scale, int groupIndex) {
        int groupSize = scale.groupSize();
        int firstUserOffset = groupIndex * groupSize;
        long firstTargetOffsetMillis = GROUP_FIRST_OFFSETS.get(groupIndex);
        long targetOffsetStepMillis = GROUP_OFFSET_STEPS.get(groupIndex);
        List<Long> userIds = LongStream.range(
                        FIRST_USER_ID + firstUserOffset,
                        FIRST_USER_ID + firstUserOffset + groupSize)
                .boxed()
                .toList();
        List<Long> targetOffsets = IntStream.range(0, groupSize)
                .mapToObj(index -> firstTargetOffsetMillis
                        + targetOffsetStepMillis * (index % OFFSET_CYCLE_SIZE))
                .toList();
        return new BoundaryGroup(
                GROUP_CODES.get(groupIndex), GROUP_REFERENCES.get(groupIndex), userIds, targetOffsets);
    }

    /**
     * 只允许性能 40K 与容量 80K 两种运行规模，避免内部夹具入口退化为任意批量造数能力。
     */
    public enum RunScale {
        PERFORMANCE_40K(5_000),
        CAPACITY_80K(10_000);

        private final int groupSize;

        RunScale(int groupSize) {
            this.groupSize = groupSize;
        }

        public int groupSize() {
            return groupSize;
        }

        public int totalRunUsers() {
            return groupSize * TOTAL_GROUPS;
        }

        public int usersPerTier() {
            return groupSize / PERSONAL_TARGET_TIERS.size();
        }
    }

    /**
     * 标识目标时间相对于订单支付截止还是硬关闭截止计算。
     */
    public enum BoundaryReference {
        EXPIRES_AT,
        HARD_CLOSE_AT
    }

    /**
     * 描述一个固定规模边界组及其逐用户毫秒偏移，集合在构造时复制为不可变值。
     *
     * @param code 稳定组代码
     * @param boundaryReference 目标截止时间类型
     * @param userIds 固定用户集合
     * @param targetOffsetMillis 与目标截止时间的逐用户毫秒偏移
     */
    public record BoundaryGroup(
            String code,
            BoundaryReference boundaryReference,
            List<Long> userIds,
            List<Long> targetOffsetMillis) {

        public BoundaryGroup {
            userIds = List.copyOf(userIds);
            targetOffsetMillis = List.copyOf(targetOffsetMillis);
        }
    }
}
