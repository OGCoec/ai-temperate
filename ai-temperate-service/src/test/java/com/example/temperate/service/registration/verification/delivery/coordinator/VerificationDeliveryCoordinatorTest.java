package com.example.temperate.service.registration.verification.delivery.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 验证注册验证码投递协调器只负责发布 RabbitMQ 消息，并把发布失败映射为受控业务异常。
 */
class VerificationDeliveryCoordinatorTest {

    @Test
    void publishesRegistrationDeliveryMessageWithCurrentOperationId() {
        VerificationDeliveryPublisher publisher = mock(VerificationDeliveryPublisher.class);
        VerificationDeliveryCoordinator coordinator =
                new VerificationDeliveryCoordinator(publisher);
        ProtectedRegistrationAccess access = mock(ProtectedRegistrationAccess.class);
        HmacIdentifier operationId = mock(HmacIdentifier.class);
        VerificationDeliveryRequest request =
                new VerificationDeliveryRequest("alice@example.test", "012345");
        Instant expiresAt = Instant.parse("2026-07-18T10:05:00Z");

        coordinator.deliver(
                access,
                VerificationChannel.EMAIL,
                VerificationDeliveryMethod.EMAIL,
                operationId,
                request,
                expiresAt);

        verify(publisher).publishRegistration(
                access,
                VerificationChannel.EMAIL,
                VerificationDeliveryMethod.EMAIL,
                operationId,
                request,
                expiresAt);
    }

    @Test
    void publishFailureReturnsControlledUnavailableError() {
        VerificationDeliveryPublisher publisher = mock(VerificationDeliveryPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher)
                .publishRegistration(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        VerificationDeliveryCoordinator coordinator =
                new VerificationDeliveryCoordinator(publisher);

        assertThatThrownBy(() -> coordinator.deliver(
                        mock(ProtectedRegistrationAccess.class),
                        VerificationChannel.EMAIL,
                        VerificationDeliveryMethod.EMAIL,
                        mock(HmacIdentifier.class),
                        new VerificationDeliveryRequest("alice@example.test", "012345"),
                        Instant.parse("2026-07-18T10:05:00Z")))
                .isInstanceOfSatisfying(
                        RegistrationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(RegistrationErrorCode.DELIVERY_UNAVAILABLE));
    }
}
