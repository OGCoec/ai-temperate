package com.example.temperate.service.auth.login.dto.result;

import java.time.Instant;
import lombok.Getter;

/**
 * 表示第一因子完成后供传输层分发正式会话或 TOTP 挑战的内部结果。
 *
 * <p>Web 适配层必须按客户端平台将其中令牌放入 Cookie 或受保护响应，不得直接记录或暴露到不适用的传输渠道。</p>
 */
@Getter
public final class LoginResult {

    private final LoginFlowStatus status;
    private final String publicId;
    private final String displayName;
    private final String accessToken;
    private final String refreshToken;
    private final String csrfToken;
    private final Instant refreshExpiresAt;
    private final String totpFlowToken;
    private final Instant totpExpiresAt;
    private final Integer attemptsRemaining;

    public LoginResult(
            String publicId,
            String displayName,
            String accessToken,
            String refreshToken,
            String csrfToken,
            Instant refreshExpiresAt) {
        this(
                LoginFlowStatus.AUTHENTICATED,
                publicId,
                displayName,
                accessToken,
                refreshToken,
                csrfToken,
                refreshExpiresAt,
                null,
                null,
                null);
    }

    private LoginResult(
            LoginFlowStatus status,
            String publicId,
            String displayName,
            String accessToken,
            String refreshToken,
            String csrfToken,
            Instant refreshExpiresAt,
            String totpFlowToken,
            Instant totpExpiresAt,
            Integer attemptsRemaining) {
        this.status = status;
        this.publicId = publicId;
        this.displayName = displayName;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.csrfToken = csrfToken;
        this.refreshExpiresAt = refreshExpiresAt;
        this.totpFlowToken = totpFlowToken;
        this.totpExpiresAt = totpExpiresAt;
        this.attemptsRemaining = attemptsRemaining;
    }

    public static LoginResult totpRequired(
            String rawFlowToken,
            Instant expiresAt,
            int attemptsRemaining) {
        if (rawFlowToken == null || rawFlowToken.isBlank()
                || expiresAt == null || attemptsRemaining <= 0) {
            throw new IllegalArgumentException("TOTP login challenge result is invalid.");
        }
        return new LoginResult(
                LoginFlowStatus.TOTP_REQUIRED,
                null,
                null,
                null,
                null,
                null,
                null,
                rawFlowToken,
                expiresAt,
                attemptsRemaining);
    }

    public boolean isAuthenticated() {
        return status == LoginFlowStatus.AUTHENTICATED;
    }
}
