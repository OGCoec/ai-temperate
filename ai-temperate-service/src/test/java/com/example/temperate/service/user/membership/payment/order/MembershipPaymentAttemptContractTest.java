package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来约束支付发起采用独立 Service 接口，并向调用方明确区分首次记录和幂等重放。
 */
final class MembershipPaymentAttemptContractTest {

    @Test
    void paymentAttemptServiceExposesOwnedOrderStartResult() throws Exception {
        Class<?> serviceType = Class.forName(
                "com.example.temperate.service.user.membership.payment.order."
                        + "MembershipPaymentAttemptService");
        Class<?> resultType = Class.forName(
                "com.example.temperate.service.user.membership.payment.order."
                        + "MembershipPaymentAttemptResult");

        Method start = serviceType.getMethod(
                "start",
                long.class,
                byte[].class,
                PaymentProviderType.class,
                String.class);

        assertThat(serviceType.isInterface()).isTrue();
        assertThat(start.getReturnType()).isEqualTo(resultType);
        assertThat(resultType.getMethod("started").getReturnType()).isEqualTo(boolean.class);
        assertThat(resultType.getMethod("snapshot").getReturnType())
                .isEqualTo(MembershipOrderSnapshot.class);
    }
}
