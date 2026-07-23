package com.example.temperate.service.registration.flow.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlow;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import java.time.Instant;

/**
 * 注册状态机的持久化边界。
 *
 * <p>用途：定义注册流程、验证码、人工校验和完成领取的状态转换契约。实现必须将依赖同一流程状态的校验与变更
 * 原子执行，避免并发请求重复消费验证码或同时完成注册。</p>
 */
public interface RegistrationFlowStore {

    boolean isBlocked(RegistrationActor actor);

    boolean recordConflict(RegistrationActor actor, Instant occurredAt);

    default boolean recordConflict(
            RegistrationActor actor,
            boolean phoneConflict,
            boolean emailConflict,
            Instant occurredAt) {
        return recordConflict(actor, occurredAt);
    }

    void create(RegistrationFlow flow);

    RegistrationFlowSnapshot getRequired(
            ProtectedRegistrationAccess access, Instant now);

    RegistrationFlowSnapshot markHumanVerified(
            ProtectedRegistrationAccess access, Instant now);

    void issueCode(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier codeDigest,
            HmacIdentifier sendOperationId,
            Instant now);

    boolean claimCodeDeliveryAttempt(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String messageId,
            int attemptNo);

    boolean releaseCodeDeliveryForRetry(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String messageId);

    boolean markCodeDeliverySucceeded(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId);

    boolean markCodeDeliveryAccepted(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String providerMessageId,
            String providerStatus);

    boolean markCodeDeliveryOutcomeUnknown(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            String safeReason);

    /** 读取 UNKNOWN 终态的固定诊断原因，用于重投时只补发审计消息而不再次调用 Provider。 */
    default String findCodeDeliveryOutcomeUnknown(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId) {
        return null;
    }

    boolean finalizeCodeDeliveryFailure(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId);

    boolean compensateCodeDeliveryFailure(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId);

    RegistrationFlowSnapshot verifyCode(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier codeDigest,
            Instant now);

    default RegistrationFlowSnapshot verifyCodes(
            ProtectedRegistrationAccess access,
            HmacIdentifier emailCodeDigest,
            HmacIdentifier phoneCodeDigest,
            Instant now) {
        throw new UnsupportedOperationException("Combined code verification is not implemented.");
    }

    /**
     * 原子领取注册完成权。
     *
     * <p>成功返回时，调用方获得该流程唯一的完成权；并发调用必须只有一个能够成功领取，后续调用由实现返回受控状态。</p>
     */
    RegistrationCompletionClaim claimCompletion(
            ProtectedRegistrationAccess access,
            HmacIdentifier claimId,
            Instant now);

    void releaseCompletionClaim(
            ProtectedRegistrationAccess access, HmacIdentifier claimId);

    void delete(ProtectedRegistrationAccess access);
}
