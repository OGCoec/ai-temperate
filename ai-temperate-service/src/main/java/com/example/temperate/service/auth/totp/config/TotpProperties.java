package com.example.temperate.service.auth.totp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定并校验普通用户 TOTP 二次认证的算法、时限和密钥保护配置。
 *
 * <p>该配置固定 RFC 6238 的兼容参数和全部短期状态边界；它不生成用户密钥，也不保存请求级流程状态。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.security.totp")
public record TotpProperties(
        @NotBlank String issuer,
        int secretBytes,
        int digits,
        @NotNull Duration period,
        int allowedDriftSteps,
        @NotNull Duration setupTtl,
        @NotNull Duration loginChallengeTtl,
        @NotNull Duration stepUpTtl,
        int maxAttempts,
        @Valid @NotNull Encryption encryption) {

    /**
     * 表示当前 TOTP 密文信封使用的 AES-GCM Key ID 和规范 Base64 密钥。
     */
    public record Encryption(
            @NotBlank String activeKeyId,
            @NotBlank String activeKeyBase64) {

        @AssertTrue(message = "TOTP encryption key must be canonical Base64 with 32 bytes")
        public boolean isValid() {
            if (activeKeyId == null
                    || !activeKeyId.matches("^[a-z0-9][a-z0-9_-]{0,31}$")
                    || activeKeyBase64 == null) {
                return false;
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(activeKeyBase64);
                return decoded.length == 32
                        && Base64.getEncoder().encodeToString(decoded)
                                .equals(activeKeyBase64);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    @AssertTrue(message = "TOTP algorithm and time bounds must match the supported contract")
    public boolean isSupportedContract() {
        return issuer != null
                && !issuer.isBlank()
                && issuer.length() <= 64
                && secretBytes == 32
                && digits == 6
                && Duration.ofSeconds(30).equals(period)
                && allowedDriftSteps == 1
                && Duration.ofMinutes(10).equals(setupTtl)
                && Duration.ofMinutes(5).equals(loginChallengeTtl)
                && Duration.ofMinutes(5).equals(stepUpTtl)
                && maxAttempts == 5;
    }
}
