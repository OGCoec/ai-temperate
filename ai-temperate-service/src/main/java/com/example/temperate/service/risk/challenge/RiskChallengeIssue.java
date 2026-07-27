package com.example.temperate.service.risk.challenge;

import java.time.Instant;

/**
 * 承载只向当前客户端返回的一次性 WAF Challenge 引用和过期时间。
 */
public record RiskChallengeIssue(
        String reference,
        Instant expiresAt) {

    @Override
    public String toString() {
        return "RiskChallengeIssue[redacted]";
    }
}
