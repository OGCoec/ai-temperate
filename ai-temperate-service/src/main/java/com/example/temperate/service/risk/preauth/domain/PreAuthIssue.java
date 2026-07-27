package com.example.temperate.service.risk.preauth.domain;

import java.time.Instant;

/**
 * 承载新签发的原始 PreAuth Token 及其过期时间，原始值只允许立即传输给对应客户端。
 */
public record PreAuthIssue(
        String rawToken,
        Instant expiresAt) {

    @Override
    public String toString() {
        return "PreAuthIssue[redacted]";
    }
}
