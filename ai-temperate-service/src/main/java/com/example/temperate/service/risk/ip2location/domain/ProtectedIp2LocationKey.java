package com.example.temperate.service.risk.ip2location.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.time.Instant;

/**
 * 表示可写入 Redis 的确定性 Key ID、版本化密文和绝对过期时间。
 */
public record ProtectedIp2LocationKey(
        HmacIdentifier keyId,
        String encryptedEnvelope,
        Instant expiresAt) {

    @Override
    public String toString() {
        return "ProtectedIp2LocationKey[redacted]";
    }
}
