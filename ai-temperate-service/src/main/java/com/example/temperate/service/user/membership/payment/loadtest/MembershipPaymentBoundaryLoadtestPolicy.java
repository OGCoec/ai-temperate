package com.example.temperate.service.user.membership.payment.loadtest;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * 定义四万用户毫秒边界测试不可配置的身份区间、时间分组、套餐分布和 TEAM 负向探针。
 *
 * <p>该策略只表达固定测试拓扑，不读取 HTTP 参数或环境配置，避免受控测试接口被扩大成任意用户批量操作能力。</p>
 */
public final class MembershipPaymentBoundaryLoadtestPolicy {

    private static final long FIRST_USER_ID = 70_000_000_000_000_000L;
    private static final int TOTAL_USERS = 40_000;
    private static final int PAGE_SIZE = 500;
    private static final int TOTAL_PAGES = TOTAL_USERS / PAGE_SIZE;
    private static final int GROUP_SIZE = 5_000;
    private static final int TOTAL_GROUPS = TOTAL_USERS / GROUP_SIZE;
    private static final int OFFSET_CYCLE_SIZE = 500;
    private static final int USERS_PER_TIER = 1_250;
    private static final int TEAM_PROBES_PER_GROUP = 25;
    private static final List<MembershipTier> PERSONAL_TARGET_TIERS =
            List.of(MembershipTier.GO, MembershipTier.PLUS, MembershipTier.PRO, MembershipTier.MAX);
    private static final List<BoundaryGroup> GROUPS = List.of(
            group("E-P1", 0, BoundaryReference.EXPIRES_AT, -1L, 0L),
            group("E-PR", 5_000, BoundaryReference.EXPIRES_AT, -1_000L, 2L),
            group("E-A1", 10_000, BoundaryReference.EXPIRES_AT, 1L, 0L),
            group("E-AR", 15_000, BoundaryReference.EXPIRES_AT, 0L, 2L),
            group("H-P1", 20_000, BoundaryReference.HARD_CLOSE_AT, -1L, 0L),
            group("H-PR", 25_000, BoundaryReference.HARD_CLOSE_AT, -1_000L, 2L),
            group("H-A1", 30_000, BoundaryReference.HARD_CLOSE_AT, 1L, 0L),
            group("H-AR", 35_000, BoundaryReference.HARD_CLOSE_AT, 0L, 2L));

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
     * @param page 固定页码，范围为 0 到 79
     * @return 不可变用户 ID 列表
     */
    public List<Long> pageUserIds(int page) {
        requireIndex(page, TOTAL_PAGES, "Token 页码");
        long pageFirstUserId = FIRST_USER_ID + (long) page * PAGE_SIZE;
        return LongStream.range(pageFirstUserId, pageFirstUserId + PAGE_SIZE).boxed().toList();
    }

    public List<BoundaryGroup> groups() {
        return GROUPS;
    }

    /**
     * 按每组连续四个 1,250 用户分区确定个人套餐，保证每个五千用户边界组独立获得完全相同的套餐分布。
     *
     * @param userId 固定模板用户 ID
     * @return GO、PLUS、PRO 或 MAX
     */
    public MembershipTier targetTier(long userId) {
        requireBoundaryUser(userId);
        int offsetInsideGroup = (int) ((userId - FIRST_USER_ID) % GROUP_SIZE);
        return PERSONAL_TARGET_TIERS.get(offsetInsideGroup / USERS_PER_TIER);
    }

    /**
     * 返回每个五千用户区段最前面的 25 个固定用户，用于先验证 TEAM 被拒绝且不产生订单。
     *
     * @param groupIndex 固定区段索引，范围为 0 到 7
     * @return 不可变的 25 用户列表
     */
    public List<Long> teamProbeUserIds(int groupIndex) {
        requireIndex(groupIndex, TOTAL_GROUPS, "区段索引");
        long groupFirstUserId = FIRST_USER_ID + (long) groupIndex * GROUP_SIZE;
        return LongStream.range(groupFirstUserId, groupFirstUserId + TEAM_PROBES_PER_GROUP)
                .boxed()
                .toList();
    }

    private void requireBoundaryUser(long userId) {
        if (!isBoundaryUser(userId)) {
            throw new IllegalArgumentException("用户不属于固定毫秒边界测试区间");
        }
    }

    private static void requireIndex(int index, int upperExclusive, String label) {
        if (index < 0 || index >= upperExclusive) {
            throw new IllegalArgumentException(label + "超出固定范围");
        }
    }

    private static BoundaryGroup group(
            String code,
            int firstUserOffset,
            BoundaryReference boundaryReference,
            long firstTargetOffsetMillis,
            long targetOffsetStepMillis) {
        List<Long> userIds = LongStream.range(
                        FIRST_USER_ID + firstUserOffset,
                        FIRST_USER_ID + firstUserOffset + GROUP_SIZE)
                .boxed()
                .toList();
        List<Long> targetOffsets = IntStream.range(0, GROUP_SIZE)
                .mapToObj(index -> firstTargetOffsetMillis
                        + targetOffsetStepMillis * (index % OFFSET_CYCLE_SIZE))
                .toList();
        return new BoundaryGroup(code, boundaryReference, userIds, targetOffsets);
    }

    /**
     * 标识目标时间相对于订单支付截止还是硬关闭截止计算。
     */
    public enum BoundaryReference {
        EXPIRES_AT,
        HARD_CLOSE_AT
    }

    /**
     * 描述一个固定的五千用户边界组及其逐用户毫秒偏移，集合在构造时复制为不可变值。
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
