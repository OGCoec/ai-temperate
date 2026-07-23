package com.example.temperate.service.registration.flow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlow;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 验证注册流程存储实现必须满足的状态转换与完成领取契约的测试。
 */
class RegistrationFlowStoreContractTest {

    @Test
    void exposesAtomicRegistrationFlowOperations() throws Exception {
        assertThat(RegistrationFlowStore.class).isInterface();
        assertReturn("isBlocked", boolean.class, RegistrationActor.class);
        assertReturn("recordConflict", boolean.class, RegistrationActor.class, Instant.class);
        assertReturn("create", void.class, RegistrationFlow.class);
        assertReturn(
                "getRequired",
                RegistrationFlowSnapshot.class,
                ProtectedRegistrationAccess.class,
                Instant.class);
        assertReturn(
                "markHumanVerified",
                RegistrationFlowSnapshot.class,
                ProtectedRegistrationAccess.class,
                Instant.class);
        assertReturn(
                "issueCode",
                void.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class,
                HmacIdentifier.class,
                Instant.class);
        assertReturn(
                "markCodeDeliverySucceeded",
                boolean.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class);
        assertReturn(
                "markCodeDeliveryAccepted",
                boolean.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class,
                String.class,
                String.class);
        assertReturn(
                "markCodeDeliveryOutcomeUnknown",
                boolean.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class,
                String.class);
        assertReturn(
                "claimCodeDeliveryAttempt",
                boolean.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class,
                String.class,
                int.class);
        assertReturn(
                "releaseCodeDeliveryForRetry",
                boolean.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class,
                String.class);
        assertReturn(
                "finalizeCodeDeliveryFailure",
                boolean.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class);
        assertReturn(
                "compensateCodeDeliveryFailure",
                boolean.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class);
        assertReturn(
                "verifyCode",
                RegistrationFlowSnapshot.class,
                ProtectedRegistrationAccess.class,
                VerificationChannel.class,
                HmacIdentifier.class,
                Instant.class);
        assertReturn(
                "claimCompletion",
                RegistrationCompletionClaim.class,
                ProtectedRegistrationAccess.class,
                HmacIdentifier.class,
                Instant.class);
        assertReturn(
                "releaseCompletionClaim",
                void.class,
                ProtectedRegistrationAccess.class,
                HmacIdentifier.class);
        assertReturn("delete", void.class, ProtectedRegistrationAccess.class);
    }

    private static void assertReturn(
            String methodName, Class<?> returnType, Class<?>... parameterTypes) throws Exception {
        assertThat(RegistrationFlowStore.class.getMethod(methodName, parameterTypes).getReturnType())
                .isEqualTo(returnType);
    }
}
