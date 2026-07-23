package com.example.temperate.service.auth.login.code.flow;

import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import java.time.Instant;

/**
 * 表示登录验证码流程在存储层读取到的当前状态快照。
 *
 * <p>该快照用于服务层决策验证码投递和登录，不包含原始流程 Token 或验证码明文。</p>
 */
public record LoginCodeFlowSnapshot(
        LoginStrategyType strategyType,
        String identifier,
        long userId,
        boolean humanVerified,
        Instant createdAt,
        Instant expiresAt,
        Instant absoluteExpiresAt) {
}
