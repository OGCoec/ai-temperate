package com.example.temperate.service.registration.verification.delivery.rabbit;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.code.flow.ProtectedLoginCodeAccess;
import com.example.temperate.service.auth.passwordreset.flow.ProtectedPasswordResetAccess;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import java.time.Duration;
import java.time.Instant;

/**
 * 发布验证码投递消息，并在发布确认成功后才返回调用方。
 *
 * <p>注册、登录和重置密码都把投递方式写入同一消息契约；旧调用未携带该字段时按逻辑渠道回退为
 * EMAIL 或 SMS。接口不选择供应商，具体供应商只能由消费者根据受保护消息和目标国家解析。</p>
 */
public interface VerificationDeliveryPublisher {

    void publishRegistration(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt);

    /** 将注册手机验证码的受控投递方式写入消息；旧 Publisher 只能接受渠道默认值。 */
    default void publishRegistration(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        if (deliveryMethod != VerificationDeliveryMethod.defaultFor(channel)) {
            throw new UnsupportedOperationException("Publisher does not support delivery method.");
        }
        publishRegistration(access, channel, operationId, request, codeExpiresAt);
    }

    void publishLogin(
            ProtectedLoginCodeAccess access,
            VerificationChannel channel,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt);

    /** 将登录手机验证码的受控投递方式写入消息；旧 Publisher 只能接受渠道默认值。 */
    default void publishLogin(
            ProtectedLoginCodeAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        if (deliveryMethod != VerificationDeliveryMethod.defaultFor(channel)) {
            throw new UnsupportedOperationException("Publisher does not support delivery method.");
        }
        publishLogin(access, channel, operationId, request, codeExpiresAt);
    }

    void publishPasswordReset(
            ProtectedPasswordResetAccess access,
            VerificationChannel channel,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt);

    /** 将重置密码手机验证码的受控投递方式写入消息；旧 Publisher 只能接受渠道默认值。 */
    default void publishPasswordReset(
            ProtectedPasswordResetAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        if (deliveryMethod != VerificationDeliveryMethod.defaultFor(channel)) {
            throw new UnsupportedOperationException("Publisher does not support delivery method.");
        }
        publishPasswordReset(access, channel, operationId, request, codeExpiresAt);
    }

    void publishRetry(VerificationDeliveryMessage current, Duration delay);

    /**
     * 将不可继续处理的原任务写入持久化终态队列；只有 Publisher Confirm 成功后才允许调用方更新流程并 ACK。
     */
    void publishTerminalFailure(
            VerificationDeliveryMessage original,
            String provider,
            String safeReason,
            boolean retryable);
}
