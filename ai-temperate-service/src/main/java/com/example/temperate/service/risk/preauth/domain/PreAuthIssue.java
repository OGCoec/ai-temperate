package com.example.temperate.service.risk.preauth.domain;

import java.time.Instant;

/**
 * 承载新签发的原始 PreAuth Token 及其过期时间，原始值只允许立即传输给对应客户端。
 */
public record PreAuthIssue(
        String rawToken,
        Instant expiresAt,
        PreAuthWebRtcPhase webRtcPhase,
        long webRtcGeneration) {

    public PreAuthIssue {
        if (rawToken == null || rawToken.isBlank()
                || expiresAt == null
                || webRtcPhase == null
                || webRtcGeneration <= 0) {
            throw new IllegalArgumentException("PreAuth issue is invalid.");
        }
    }

    /**
     * 为不关心 WebRTC 传输头的既有调用补齐默认 REQUIRED generation；生产签发路径应使用完整构造器。
     */
    public PreAuthIssue(String rawToken, Instant expiresAt) {
        this(rawToken, expiresAt, PreAuthWebRtcPhase.REQUIRED, 1L);
    }

    @Override
    public String toString() {
        return "PreAuthIssue[redacted]";
    }
}
