package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import java.time.Duration;

/**
 * 保存 Microsoft Graph 邮件投递所需的 OAuth 凭据、远端入口和分阶段超时边界。
 *
 * <p>发件身份不再由配置注入请求，而是由 {@code /me/sendMail} 根据访问令牌主体确定；密钥必须由
 * 环境变量或 Secret 管理服务提供，字符串表示始终保持脱敏。</p>
 */
public record MicrosoftGraphApiProperties(
        String clientId,
        String clientSecret,
        String refreshToken,
        String tokenUri,
        String scope,
        String sendUri,
        Duration oauthTimeout,
        Duration sendTimeout) {

    public MicrosoftGraphApiProperties {
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
        requireText(refreshToken, "refreshToken");
        requireText(tokenUri, "tokenUri");
        requireText(scope, "scope");
        requireText(sendUri, "sendUri");
        requirePositive(oauthTimeout, "oauthTimeout");
        requirePositive(sendTimeout, "sendTimeout");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @Override
    public String toString() {
        return "MicrosoftGraphApiProperties[credentials=protected, endpoints=protected]";
    }
}
