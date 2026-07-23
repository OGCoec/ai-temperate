package com.example.temperate.service.auth.passwordreset.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.enums.VerificationChannel;
import java.time.Instant;

/**
 * 定义密码重置流程、验证码、风险控制和一次性找回凭据的原子存储契约。
 *
 * <p>实现必须在并发请求下保证验证码只能有限次验证、找回凭据只能由一个完成请求领取，且失败补偿不会
 * 撤销其他请求的操作。</p>
 */
public interface PasswordResetFlowStore {

    boolean isBlocked(HmacIdentifier deviceHash, HmacIdentifier globalDeviceHash);

    void create(
            ProtectedPasswordResetAccess access,
            VerificationChannel channel,
            String identifier,
            long userId,
            Instant createdAt);

    PasswordResetFlowSnapshot getRequired(
            ProtectedPasswordResetAccess access, Instant now);

    PasswordResetFlowSnapshot markHumanVerified(
            ProtectedPasswordResetAccess access, Instant now);

    void issueCode(
            ProtectedPasswordResetAccess access,
            HmacIdentifier digest,
            HmacIdentifier operationId,
            Instant now);

    boolean claimDeliveryAttempt(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String messageId,
            int attemptNo);

    boolean releaseDeliveryForRetry(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String messageId);

    boolean markDeliverySucceeded(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId);

    boolean markDeliveryAccepted(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String providerMessageId,
            String providerStatus);

    boolean markDeliveryOutcomeUnknown(
            ProtectedPasswordResetAccess access,
            HmacIdentifier operationId,
            String safeReason);

    /** 读取 UNKNOWN 终态的固定诊断原因，避免重投时再次发送密码重置验证码。 */
    default String findDeliveryOutcomeUnknown(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId) {
        return null;
    }

    boolean finalizeDeliveryFailure(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId);

    boolean compensateDeliveryFailure(
            ProtectedPasswordResetAccess access, HmacIdentifier operationId);

    long verifyAndCreateForgetToken(
            ProtectedPasswordResetAccess access,
            HmacIdentifier digest,
            HmacIdentifier forgetTokenHash,
            Instant now);

    long claimForgetToken(
            HmacIdentifier forgetTokenHash,
            HmacIdentifier deviceHash,
            HmacIdentifier claimId,
            Instant now);

    void consumeForgetToken(HmacIdentifier forgetTokenHash, HmacIdentifier claimId);

    void releaseForgetToken(HmacIdentifier forgetTokenHash, HmacIdentifier claimId);
}
