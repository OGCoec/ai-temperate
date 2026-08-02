package com.example.temperate.service.user.profile.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定普通用户资料缓存的独立 AES-256 ID 密钥和随机 TTL 安全边界。
 *
 * <p>密钥只能由外部环境提供且不会通过 {@link #toString()} 暴露；TTL 被限制在项目统一的五至十五分钟
 * 正向缓存窗口内。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.user-profile-cache")
public record UserProfileCacheProperties(
        String idEncryptionKeyBase64,
        @NotNull Duration minimumTtl,
        @NotNull Duration maximumTtl) {

    private static final Duration LOWEST_TTL = Duration.ofMinutes(5);
    private static final Duration HIGHEST_TTL = Duration.ofMinutes(15);

    @AssertTrue(message = "User profile cache ID key must be canonical Base64 containing 32 bytes")
    public boolean isEncryptionKeyValid() {
        if (idEncryptionKeyBase64 == null || idEncryptionKeyBase64.isBlank()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(idEncryptionKeyBase64);
            return decoded.length == 32
                    && Base64.getEncoder().encodeToString(decoded)
                    .equals(idEncryptionKeyBase64);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @AssertTrue(message = "User profile cache TTL range must stay between five and fifteen minutes")
    public boolean isTtlRangeValid() {
        return minimumTtl != null
                && maximumTtl != null
                && minimumTtl.compareTo(LOWEST_TTL) >= 0
                && maximumTtl.compareTo(HIGHEST_TTL) <= 0
                && minimumTtl.compareTo(maximumTtl) <= 0;
    }

    @Override
    public String toString() {
        return "UserProfileCacheProperties[idEncryptionKeyBase64=redacted, minimumTtl="
                + minimumTtl
                + ", maximumTtl="
                + maximumTtl
                + "]";
    }
}
