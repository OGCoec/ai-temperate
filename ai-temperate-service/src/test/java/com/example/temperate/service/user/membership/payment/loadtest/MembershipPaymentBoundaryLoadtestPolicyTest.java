package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 用于验证八万账号夹具及 40K、80K 两种固定运行规模的时间分组和套餐映射不可被运行参数改变。
 */
class MembershipPaymentBoundaryLoadtestPolicyTest {

    private final MembershipPaymentBoundaryLoadtestPolicy policy =
            new MembershipPaymentBoundaryLoadtestPolicy();

    @Test
    void shouldExposeOnlyTheCanonicalUserRangeAndTokenPages() {
        assertThat(policy.firstUserId()).isEqualTo(70_000_000_000_000_000L);
        assertThat(policy.lastUserId()).isEqualTo(70_000_000_000_079_999L);
        assertThat(policy.totalUsers()).isEqualTo(80_000);
        assertThat(policy.pageUserIds(0))
                .hasSize(500)
                .startsWith(70_000_000_000_000_000L)
                .endsWith(70_000_000_000_000_499L);
        assertThat(policy.pageUserIds(159))
                .hasSize(500)
                .startsWith(70_000_000_000_079_500L)
                .endsWith(70_000_000_000_079_999L);
        assertThat(policy.isBoundaryUser(70_000_000_000_000_000L)).isTrue();
        assertThat(policy.isBoundaryUser(70_000_000_000_079_999L)).isTrue();
        assertThat(policy.isBoundaryUser(69_999_999_999_999_999L)).isFalse();
        assertThat(policy.isBoundaryUser(70_000_000_000_080_000L)).isFalse();
        assertThatThrownBy(() -> policy.pageUserIds(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.pageUserIds(160))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeEightImmutableBoundaryGroupsWithExactOffsets() {
        List<MembershipPaymentBoundaryLoadtestPolicy.BoundaryGroup> groups =
                policy.groups(MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K);

        assertThat(groups).hasSize(8);
        assertThat(groups).extracting(MembershipPaymentBoundaryLoadtestPolicy.BoundaryGroup::code)
                .containsExactly("E-P1", "E-PR", "E-A1", "E-AR", "H-P1", "H-PR", "H-A1", "H-AR");
        assertThat(groups).allSatisfy(group -> {
            assertThat(group.userIds()).hasSize(5_000);
            assertThat(group.targetOffsetMillis()).hasSize(5_000);
        });
        assertThat(groups.get(0).userIds())
                .startsWith(70_000_000_000_000_000L)
                .endsWith(70_000_000_000_004_999L);
        assertThat(groups.get(7).userIds())
                .startsWith(70_000_000_000_035_000L)
                .endsWith(70_000_000_000_039_999L);
        assertThat(groups.get(0).targetOffsetMillis()).containsOnly(-1L);
        assertThat(groups.get(1).targetOffsetMillis())
                .startsWith(-1_000L, -998L, -996L)
                .containsSequence(-6L, -4L, -2L, -1_000L, -998L)
                .endsWith(-6L, -4L, -2L);
        assertThat(groups.get(2).targetOffsetMillis()).containsOnly(1L);
        assertThat(groups.get(3).targetOffsetMillis())
                .startsWith(0L, 2L, 4L)
                .containsSequence(994L, 996L, 998L, 0L, 2L)
                .endsWith(994L, 996L, 998L);
        assertThat(groups.get(4).targetOffsetMillis()).containsOnly(-1L);
        assertThat(groups.get(5).targetOffsetMillis())
                .startsWith(-1_000L, -998L, -996L)
                .containsSequence(-6L, -4L, -2L, -1_000L, -998L)
                .endsWith(-6L, -4L, -2L);
        assertThat(groups.get(6).targetOffsetMillis()).containsOnly(1L);
        assertThat(groups.get(7).targetOffsetMillis())
                .startsWith(0L, 2L, 4L)
                .containsSequence(994L, 996L, 998L, 0L, 2L)
                .endsWith(994L, 996L, 998L);
        assertThatThrownBy(() -> groups.add(groups.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldExposeEightTenThousandUserCapacityGroupsWithoutStretchingOffsets() {
        List<MembershipPaymentBoundaryLoadtestPolicy.BoundaryGroup> groups =
                policy.groups(MembershipPaymentBoundaryLoadtestPolicy.RunScale.CAPACITY_80K);

        assertThat(groups).hasSize(8).allSatisfy(group -> {
            assertThat(group.userIds()).hasSize(10_000);
            assertThat(group.targetOffsetMillis()).hasSize(10_000);
        });
        assertThat(groups.getFirst().userIds())
                .startsWith(70_000_000_000_000_000L)
                .endsWith(70_000_000_000_009_999L);
        assertThat(groups.getLast().userIds())
                .startsWith(70_000_000_000_070_000L)
                .endsWith(70_000_000_000_079_999L);
        assertThat(groups.get(1).targetOffsetMillis())
                .startsWith(-1_000L, -998L, -996L)
                .containsSequence(-6L, -4L, -2L, -1_000L, -998L)
                .endsWith(-6L, -4L, -2L);
    }

    @Test
    void shouldDistributeEveryGroupEquallyAcrossFourPersonalTiers() {
        for (MembershipPaymentBoundaryLoadtestPolicy.RunScale scale
                : MembershipPaymentBoundaryLoadtestPolicy.RunScale.values()) {
            long expectedPerTier = scale == MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K
                    ? 1_250L : 2_500L;
            for (MembershipPaymentBoundaryLoadtestPolicy.BoundaryGroup group : policy.groups(scale)) {
            Map<MembershipTier, Long> counts = group.userIds().stream()
                    .collect(Collectors.groupingBy(
                            userId -> policy.targetTier(scale, userId), Collectors.counting()));
            assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                    MembershipTier.GO, expectedPerTier,
                    MembershipTier.PLUS, expectedPerTier,
                    MembershipTier.PRO, expectedPerTier,
                    MembershipTier.MAX, expectedPerTier));
            }
        }
    }

    @Test
    void shouldExposeTwentyFiveDeterministicTeamRejectionUsersPerGroup() {
        assertThat(policy.teamProbeUserIds(
                        MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K, 0))
                .hasSize(25)
                .startsWith(70_000_000_000_000_000L)
                .endsWith(70_000_000_000_000_024L);
        assertThat(policy.teamProbeUserIds(
                        MembershipPaymentBoundaryLoadtestPolicy.RunScale.CAPACITY_80K, 7))
                .hasSize(25)
                .startsWith(70_000_000_000_070_000L)
                .endsWith(70_000_000_000_070_024L);
        assertThatThrownBy(() -> policy.teamProbeUserIds(
                        MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.teamProbeUserIds(
                        MembershipPaymentBoundaryLoadtestPolicy.RunScale.CAPACITY_80K, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
