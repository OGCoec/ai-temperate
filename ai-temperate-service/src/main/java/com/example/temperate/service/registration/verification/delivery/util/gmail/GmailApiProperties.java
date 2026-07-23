package com.example.temperate.service.registration.verification.delivery.util.gmail;

import java.time.Duration;

/**
 * 保存 Gmail API 投递所需的 OAuth 凭据、端点和请求超时。
 *
 * <p>该类型只作为配置值载体，实际密钥必须来自环境变量或 Secret 管理服务，禁止把生产 Secret 写入 YAML 默认值。</p>
 */
public record GmailApiProperties(
        String clientId,
        String clientSecret,
        String refreshToken,
        String fromAddress,
        String tokenUri,
        String sendUri,
        Duration requestTimeout) {

    public GmailApiProperties {
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
        requireText(refreshToken, "refreshToken");
        requireText(fromAddress, "fromAddress");
        requireText(tokenUri, "tokenUri");
        requireText(sendUri, "sendUri");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
