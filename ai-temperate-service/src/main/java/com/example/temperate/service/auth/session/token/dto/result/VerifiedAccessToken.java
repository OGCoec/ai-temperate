package com.example.temperate.service.auth.session.token.dto.result;

import java.time.Instant;

/**
 * 表示访问令牌经签名、结构和时间字段校验后的内部结果。
 *
 * <p>该对象保留过期状态供刷新/登出等特定流程判断，普通 API 仍必须拒绝已过期令牌。</p>
 */
public record VerifiedAccessToken(
        String publicId,
        String tokenId,
        int schemaVersion,
        Instant issuedAt,
        Instant expiresAt,
        boolean expired) {
}
