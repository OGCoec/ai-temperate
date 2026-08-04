package com.example.temperate.service.auth.session.access.dto;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import java.time.Instant;
import java.util.Objects;

/**
 * 表示普通用户请求认证后的主体与可选 AT 续签结果，不改变原业务接口响应体。
 */
public record SessionAccessResult(
        SessionPrincipal principal,
        boolean renewed,
        String renewedAccessToken,
        Instant refreshExpiresAt) {

    public SessionAccessResult {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(refreshExpiresAt, "refreshExpiresAt must not be null");
        if (renewed && (renewedAccessToken == null || renewedAccessToken.isBlank())) {
            throw new IllegalArgumentException("Renewed access token is required after renewal.");
        }
        if (!renewed && renewedAccessToken != null) {
            throw new IllegalArgumentException("Unrenewed result must not contain a new access token.");
        }
    }

    @Override
    public String toString() {
        return "SessionAccessResult[redacted]";
    }
}
