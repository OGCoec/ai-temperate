package com.example.temperate.service.auth.totp.stepup.store;

import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import java.time.Duration;
import java.time.Instant;

/**
 * 定义验证码复验流程标记和一次性敏感操作凭证的 Redis 原子状态边界。
 */
public interface TotpStepUpStore {

    void bindCodeFlow(
            String rawFlowToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant createdAt,
            Duration ttl);

    void requireCodeFlow(
            String rawFlowToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now);

    void promoteCodeFlowToProof(
            String rawFlowToken,
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now,
            Duration ttl);

    void createProof(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant createdAt,
            Duration ttl);

    void requireProof(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now);

    void recordProofFailure(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now);

    void consumeProof(
            String rawProofToken,
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            Instant now);
}
