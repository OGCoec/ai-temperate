package com.example.temperate.service.user.membership.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.config.MembershipQuotaProperties;
import java.time.Duration;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/**
 * 验证七档会员额度均来自统一配置，且缺档、负数或错误周期不能构建额度服务。
 */
class MembershipQuotaPlanServiceImplTest {

    @Test
    void mapsEveryMembershipTierToItsSevenDayQuota() {
        MembershipQuotaPlanServiceImpl service =
                new MembershipQuotaPlanServiceImpl(properties());

        assertThat(service.getRequired(MembershipTier.FREE).totalMinor())
                .isEqualTo(5_000L);
        assertThat(service.getRequired(MembershipTier.GO).totalMinor())
                .isEqualTo(50_000L);
        assertThat(service.getRequired(MembershipTier.EDU).totalMinor())
                .isEqualTo(80_000L);
        assertThat(service.getRequired(MembershipTier.TEAM).totalMinor())
                .isEqualTo(180_000L);
        assertThat(service.getRequired(MembershipTier.PLUS).totalMinor())
                .isEqualTo(200_000L);
        assertThat(service.getRequired(MembershipTier.PRO).totalMinor())
                .isEqualTo(1_000_000L);
        assertThat(service.getRequired(MembershipTier.MAX).totalMinor())
                .isEqualTo(5_000_000L);
        assertThat(service.getRequired(MembershipTier.MAX).period())
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    void rejectsMissingTier() {
        MembershipQuotaProperties valid = properties();
        EnumMap<MembershipTier, Long> incomplete =
                new EnumMap<>(valid.limits());
        incomplete.remove(MembershipTier.TEAM);

        assertThatThrownBy(() -> new MembershipQuotaPlanServiceImpl(
                new MembershipQuotaProperties(Duration.ofDays(7), incomplete)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveQuotaAndWrongPeriod() {
        EnumMap<MembershipTier, Long> invalid =
                new EnumMap<>(properties().limits());
        invalid.put(MembershipTier.FREE, -1L);

        assertThatThrownBy(() -> new MembershipQuotaPlanServiceImpl(
                new MembershipQuotaProperties(Duration.ofDays(7), invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipQuotaPlanServiceImpl(
                new MembershipQuotaProperties(Duration.ofDays(1), properties().limits())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MembershipQuotaProperties properties() {
        EnumMap<MembershipTier, Long> limits =
                new EnumMap<>(MembershipTier.class);
        limits.put(MembershipTier.FREE, 5_000L);
        limits.put(MembershipTier.GO, 50_000L);
        limits.put(MembershipTier.EDU, 80_000L);
        limits.put(MembershipTier.TEAM, 180_000L);
        limits.put(MembershipTier.PLUS, 200_000L);
        limits.put(MembershipTier.PRO, 1_000_000L);
        limits.put(MembershipTier.MAX, 5_000_000L);
        return new MembershipQuotaProperties(Duration.ofDays(7), limits);
    }
}
