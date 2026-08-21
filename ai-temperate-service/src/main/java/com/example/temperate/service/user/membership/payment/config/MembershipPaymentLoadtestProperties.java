package com.example.temperate.service.user.membership.payment.config;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 该配置是来约束会员支付压测专用 AT-only 认证开关和既有用户白名单，不负责签发或保存访问令牌。
 */
@Validated
@ConfigurationProperties(prefix = "app.membership-payment.loadtest")
public record MembershipPaymentLoadtestProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue List<Long> allowedUserIds) {

    public MembershipPaymentLoadtestProperties {
        allowedUserIds = allowedUserIds == null ? List.of() : List.copyOf(allowedUserIds);
        if (allowedUserIds.size() > 100
                || allowedUserIds.stream().anyMatch(value -> value == null || value <= 0L)
                || new HashSet<>(allowedUserIds).size() != allowedUserIds.size()) {
            throw new IllegalArgumentException(
                    "Membership payment loadtest allowlist must contain at most 100 unique positive user IDs.");
        }
        if (enabled && allowedUserIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Membership payment loadtest allowlist is required when enabled.");
        }
    }
}
