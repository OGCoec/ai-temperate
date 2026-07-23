package com.example.temperate.service.auth.session.authentication.dto.result;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 表示会话认证成功后返回给传输层的主体、短期 AT、CSRF 与刷新会话到期信息。
 *
 * <p>传输层必须按客户端平台选择安全存储方式，不能无差别序列化敏感认证材料。</p>
 */
@Getter
@RequiredArgsConstructor
public final class SessionAuthenticationResult {

    private final SessionPrincipal principal;
    private final String accessToken;
    private final String csrfToken;
    private final Instant refreshExpiresAt;
}
