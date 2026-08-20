package com.example.temperate.service.auth.login.code.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import java.time.Instant;

/**
 * 定义登录验证码流程状态的持久化与原子状态转换契约。
 *
 * <p>实现必须保证流程、验证码、投递状态和失败计数的更新不会在并发请求间产生半完成状态。</p>
 */
public interface LoginCodeFlowStore {

    void create(
            ProtectedLoginCodeAccess access,
            LoginStrategyType type,
            LoginCodePurpose purpose,
            String identifier,
            long userId,
            Instant createdAt);

    LoginCodeFlowSnapshot getRequired(ProtectedLoginCodeAccess access, Instant now);

    LoginCodeFlowSnapshot markHumanVerified(ProtectedLoginCodeAccess access, Instant now);

    void issueCode(
            ProtectedLoginCodeAccess access,
            HmacIdentifier digest,
            HmacIdentifier operationId,
            Instant now);

    boolean claimDeliveryAttempt(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String messageId,
            int attemptNo);

    boolean releaseDeliveryForRetry(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String messageId);

    boolean markDeliverySucceeded(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId);

    boolean markDeliveryAccepted(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String providerMessageId,
            String providerStatus);

    boolean markDeliveryOutcomeUnknown(
            ProtectedLoginCodeAccess access,
            HmacIdentifier operationId,
            String safeReason);

    /** 读取 UNKNOWN 终态的固定诊断原因，避免审计发布失败重投时再次调用 Provider。 */
    default String findDeliveryOutcomeUnknown(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId) {
        return null;
    }

    boolean finalizeDeliveryFailure(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId);

    boolean compensateDeliveryFailure(
            ProtectedLoginCodeAccess access, HmacIdentifier operationId);

    LoginCodeFlowSnapshot verifyCode(
            ProtectedLoginCodeAccess access, HmacIdentifier digest, Instant now);

    void delete(ProtectedLoginCodeAccess access);
}
