package com.example.temperate.service.auth.identity.bloom;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.util.Objects;

/**
 * 承载一次 Bloom 更新所需的内部用户 ID 与经过用途隔离 HMAC 保护的联系方式。
 *
 * <p>历史账号允许手机号为空，因此构建阶段的 protectedPhone 可以为空；新注册流程仍会同时提供邮箱和
 * E.164 手机号。</p>
 */
public record ProtectedIdentityPresenceRecord(
        long userId,
        HmacIdentifier protectedEmail,
        HmacIdentifier protectedPhone) {

    public ProtectedIdentityPresenceRecord {
        if (userId <= 0) {
            throw new IllegalArgumentException("Identity presence userId must be positive.");
        }
        Objects.requireNonNull(protectedEmail, "protectedEmail must not be null");
    }
}
