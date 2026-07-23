package com.example.temperate.service.auth.login.dto.result;

import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 表示登录成功后供传输层分发认证材料的内部结果。
 *
 * <p>Web 适配层必须按客户端平台将其中令牌放入 Cookie 或受保护响应，不得直接记录或暴露到不适用的传输渠道。</p>
 */
@Getter
@RequiredArgsConstructor
public final class LoginResult {

    private final String publicId;
    private final String displayName;
    private final String accessToken;
    private final String refreshToken;
    private final String csrfToken;
    private final Instant refreshExpiresAt;
}
