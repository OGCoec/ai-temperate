package com.example.temperate.service.risk.preauth.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import java.time.Duration;
import java.util.Objects;

/**
 * 描述刷新会话或管理员会话与已认证 PreAuth 之间必须由同一个 Redis Lua 校验和续期的绑定。
 *
 * <p>该对象只携带 HMAC 摘要和固定枚举，不携带原始 PreAuth、Refresh Token 或管理员 Token；
 * 存储实现使用它防止不同设备、不同作用域或不同会话的 PreAuth 被拼接续期。</p>
 */
public record PreAuthSessionBinding(
        RiskScope scope,
        HmacIdentifier tokenDigest,
        HmacIdentifier deviceDigest,
        RiskSessionType sessionType,
        HmacIdentifier sessionRefDigest,
        Duration ttl,
        boolean promoteAnonymous) {

    public PreAuthSessionBinding {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(tokenDigest, "tokenDigest must not be null");
        Objects.requireNonNull(deviceDigest, "deviceDigest must not be null");
        Objects.requireNonNull(sessionType, "sessionType must not be null");
        Objects.requireNonNull(sessionRefDigest, "sessionRefDigest must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (sessionType == RiskSessionType.NONE) {
            throw new IllegalArgumentException("Authenticated PreAuth session type is required.");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Authenticated PreAuth TTL must be positive.");
        }
    }
}
