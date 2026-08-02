package com.example.temperate.service.user.membership.config;

import com.example.temperate.model.auth.enums.MembershipTier;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定七档会员套餐的额度最小单位和固定七天滚动周期配置。
 *
 * <p>配置必须覆盖全部后端枚举且每档额度为正数，防止新增等级后静默沿用错误额度。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.membership-quota")
public record MembershipQuotaProperties(
        @NotNull Duration period,
        @NotNull Map<MembershipTier, Long> limits) {

    private static final Duration REQUIRED_PERIOD = Duration.ofDays(7);

    public MembershipQuotaProperties {
        // 配置属性在部分精简上下文中不会自动触发 Bean Validation，因此必须在绑定构造阶段拒绝不完整的套餐表，避免服务以错误额度启动。
        validatePeriod(period);
        validateLimits(limits);
        if (limits != null) {
            EnumMap<MembershipTier, Long> copied =
                    new EnumMap<>(MembershipTier.class);
            copied.putAll(limits);
            limits = Map.copyOf(copied);
        }
    }

    private static void validatePeriod(Duration period) {
        if (!REQUIRED_PERIOD.equals(period)) {
            throw new IllegalArgumentException(
                    "Membership quota period must be exactly seven days");
        }
    }

    private static void validateLimits(Map<MembershipTier, Long> limits) {
        if (limits == null || limits.size() != MembershipTier.values().length) {
            throw new IllegalArgumentException(
                    "Membership quota limits must contain every tier with a positive total");
        }
        for (MembershipTier tier : MembershipTier.values()) {
            Long totalMinor = limits.get(tier);
            if (totalMinor == null || totalMinor <= 0L) {
                throw new IllegalArgumentException(
                        "Membership quota limits must contain every tier with a positive total");
            }
        }
    }

    @AssertTrue(message = "Membership quota period must be exactly seven days")
    public boolean isPeriodValid() {
        return REQUIRED_PERIOD.equals(period);
    }

    @AssertTrue(message = "Membership quota limits must contain every tier with a positive total")
    public boolean areLimitsValid() {
        if (limits == null || limits.size() != MembershipTier.values().length) {
            return false;
        }
        for (MembershipTier tier : MembershipTier.values()) {
            Long totalMinor = limits.get(tier);
            if (totalMinor == null || totalMinor <= 0L) {
                return false;
            }
        }
        return true;
    }
}
