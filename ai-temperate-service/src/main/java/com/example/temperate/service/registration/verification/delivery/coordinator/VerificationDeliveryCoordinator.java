package com.example.temperate.service.registration.verification.delivery.coordinator;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 注册验证码投递协调器，负责把已登记的验证码发送操作发布到 RabbitMQ。
 *
 * <p>该协调器不再直接调用邮箱或短信供应商；发布确认失败会回到调用方，由注册流程按 operationId 做受控补偿。</p>
 */
@Component
public final class VerificationDeliveryCoordinator {

    private final VerificationDeliveryPublisher deliveryPublisher;

    public VerificationDeliveryCoordinator(VerificationDeliveryPublisher deliveryPublisher) {
        this.deliveryPublisher =
                Objects.requireNonNull(deliveryPublisher, "deliveryPublisher must not be null");
    }

    public void deliver(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier sendOperationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        deliver(
                access,
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                sendOperationId,
                request,
                codeExpiresAt);
    }

    /**
     * 将服务端已校验的投递方式写入注册投递消息；发布确认失败仍由上层按 operationId 精确补偿。
     */
    public void deliver(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier sendOperationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        Objects.requireNonNull(access, "access must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(deliveryMethod, "deliveryMethod must not be null");
        Objects.requireNonNull(sendOperationId, "sendOperationId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(codeExpiresAt, "codeExpiresAt must not be null");
        try {
            deliveryPublisher.publishRegistration(
                    access,
                    channel,
                    deliveryMethod,
                    sendOperationId,
                    request,
                    codeExpiresAt);
        } catch (RuntimeException exception) {
            throw new RegistrationException(
                    RegistrationErrorCode.DELIVERY_UNAVAILABLE,
                    "Verification delivery is temporarily unavailable.",
                    exception);
        }
    }
}
