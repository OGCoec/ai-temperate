package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 该测试是来验证会员支付耗时观测属性能通过Spring上下文完成配置绑定，不启动Web服务或外部基础设施连接。
 */
final class MembershipPaymentObservabilityConfigurationTest {

    @Test
    void bindsAllObservabilityPropertiesInApplicationContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(MembershipPaymentConfiguration.class)
                .withPropertyValues(
                        "app.membership-payment.observability.enabled=true",
                        "app.membership-payment.observability.detail-log-enabled=true",
                        "app.membership-payment.observability.sample-rate=0.25",
                        "app.membership-payment.observability.slow-threshold=PT0.75S",
                        "app.membership-payment.observability.force-log-operations="
                                + "ORDER_CREATE,PAYMENT_ATTEMPT,RABBIT_PENDING,RABBIT_CLOSING")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(MembershipPaymentObservabilityProperties.class);
                    MembershipPaymentObservabilityProperties properties = context.getBean(
                            MembershipPaymentObservabilityProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.detailLogEnabled()).isTrue();
                    assertThat(properties.sampleRate()).isEqualTo(0.25D);
                    assertThat(properties.slowThreshold())
                            .isEqualTo(Duration.ofMillis(750));
                    assertThat(properties.forceLogOperations()).containsExactlyInAnyOrder(
                            MembershipPaymentOperation.ORDER_CREATE,
                            MembershipPaymentOperation.PAYMENT_ATTEMPT,
                            MembershipPaymentOperation.RABBIT_PENDING,
                            MembershipPaymentOperation.RABBIT_CLOSING);
                });
    }
}
