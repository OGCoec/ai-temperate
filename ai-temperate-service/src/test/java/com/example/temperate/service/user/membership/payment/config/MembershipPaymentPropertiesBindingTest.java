package com.example.temperate.service.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 该测试是来验证会员支付配置在保留兼容构造器后仍由 Spring 使用规范构造器绑定，避免 BAR Bean 链因无参实例化失败。
 */
class MembershipPaymentPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MembershipPaymentConfiguration.class);

    @Test
    void bindsCanonicalRecordConstructorWhenCompatibilityConstructorAlsoExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MembershipPaymentProperties.class);
            assertThat(context.getBean(MembershipPaymentProperties.class).defaultProvider())
                    .isEqualTo(PaymentProviderType.LOCAL_SIMULATOR);
            assertThat(context.getBean(MembershipPaymentProperties.class)
                    .callback()
                    .maxBatchesPerRun()).isEqualTo(50);
            assertThat(context.getBean(MembershipPaymentProperties.class)
                    .orderPersist()
                    .maxBatchesPerRun()).isEqualTo(50);
        });
    }

    @Test
    void registersTwoIndependentSingleThreadPaymentSchedulers() {
        new ApplicationContextRunner()
                .withUserConfiguration(MembershipPaymentSchedulingConfiguration.class)
                .withPropertyValues("app.membership-payment.enabled=true")
                .run(context -> {
                    ThreadPoolTaskScheduler callbackScheduler = context.getBean(
                            MembershipPaymentSchedulingConfiguration.CALLBACK_TASK_SCHEDULER,
                            ThreadPoolTaskScheduler.class);
                    ThreadPoolTaskScheduler orderPersistScheduler = context.getBean(
                            MembershipPaymentSchedulingConfiguration.ORDER_PERSIST_TASK_SCHEDULER,
                            ThreadPoolTaskScheduler.class);

                    assertThat(callbackScheduler).isNotSameAs(orderPersistScheduler);
                    assertThat(callbackScheduler.getScheduledThreadPoolExecutor()
                            .getCorePoolSize()).isEqualTo(1);
                    assertThat(orderPersistScheduler.getScheduledThreadPoolExecutor()
                            .getCorePoolSize()).isEqualTo(1);
                });
    }
}
