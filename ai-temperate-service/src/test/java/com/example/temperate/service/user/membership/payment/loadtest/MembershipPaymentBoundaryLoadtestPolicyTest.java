package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 用于验证四万用户毫秒边界测试的固定用户区间、时间分组和套餐映射不可被运行参数改变。
 */
class MembershipPaymentBoundaryLoadtestPolicyTest {

    private final MembershipPaymentBoundaryLoadtestPolicy policy =
            new MembershipPaymentBoundaryLoadtestPolicy();

    @Test
    void shouldExposeOnlyTheCanonicalUserRangeAndTokenPages() {
        assertThat(policy.firstUserId()).isEqualTo(70_000_000_000_000_000L);
        assertThat(policy.lastUserId()).isEqualTo(70_000_000_000_039_999L);
        assertThat(policy.totalUsers()).isEqualTo(40_000);
        assertThat(policy.pageUserIds(0))
                .hasSize(500)
                .startsWith(70_000_000_000_000_000L)
                .endsWith(70_000_000_000_000_499L);
        assertThat(policy.pageUserIds(79))
                .hasSize(500)
                .startsWith(70_000_000_000_039_500L)
                .endsWith(70_000_000_000_039_999L);
        assertThat(policy.isBoundaryUser(70_000_000_000_000_000L)).isTrue();
        assertThat(policy.isBoundaryUser(70_000_000_000_039_999L)).isTrue();
        assertThat(policy.isBoundaryUser(69_999_999_999_999_999L)).isFalse();
        assertThat(policy.isBoundaryUser(70_000_000_000_040_000L)).isFalse();
        assertThatThrownBy(() -> policy.pageUserIds(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.pageUserIds(80))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeEightImmutableBoundaryGroupsWithExactOffsets() {
        List<MembershipPaymentBoundaryLoadtestPolicy.BoundaryGroup> groups = policy.groups();

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
    void shouldDistributeEveryGroupEquallyAcrossFourPersonalTiers() {
        for (MembershipPaymentBoundaryLoadtestPolicy.BoundaryGroup group : policy.groups()) {
            Map<MembershipTier, Long> counts = group.userIds().stream()
                    .collect(Collectors.groupingBy(policy::targetTier, Collectors.counting()));
            assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                    MembershipTier.GO, 1_250L,
                    MembershipTier.PLUS, 1_250L,
                    MembershipTier.PRO, 1_250L,
                    MembershipTier.MAX, 1_250L));
        }
    }

    @Test
    void shouldExposeTwentyFiveDeterministicTeamRejectionUsersPerGroup() {
        assertThat(policy.teamProbeUserIds(0))
                .hasSize(25)
                .startsWith(70_000_000_000_000_000L)
                .endsWith(70_000_000_000_000_024L);
        assertThat(policy.teamProbeUserIds(7))
                .hasSize(25)
                .startsWith(70_000_000_000_035_000L)
                .endsWith(70_000_000_000_035_024L);
        assertThatThrownBy(() -> policy.teamProbeUserIds(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.teamProbeUserIds(8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
