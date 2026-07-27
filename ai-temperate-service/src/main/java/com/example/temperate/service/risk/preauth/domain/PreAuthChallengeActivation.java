package com.example.temperate.service.risk.preauth.domain;

import java.time.Instant;

/**
 * 返回单个 PreAuth Hash 内已经创建或复用的活动 Challenge Nonce 与过期时间。
 */
public record PreAuthChallengeActivation(
        String nonce,
        Instant expiresAt,
        boolean newlyIssued) {

    public PreAuthChallengeActivation {
        if (nonce == null || nonce.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("PreAuth challenge activation is invalid.");
        }
    }
}
