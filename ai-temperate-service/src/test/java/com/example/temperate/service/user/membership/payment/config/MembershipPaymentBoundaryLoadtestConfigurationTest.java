package com.example.temperate.service.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryLoadtestPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 该测试是来确认毫秒边界固定策略由会员支付配置显式注册，确保夹具 Service 在 6655 启动时可构造。
 */
final class MembershipPaymentBoundaryLoadtestConfigurationTest {

    @Test
    void registersTheImmutableBoundaryPolicy() {
        new ApplicationContextRunner()
                .withUserConfiguration(MembershipPaymentConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(MembershipPaymentBoundaryLoadtestPolicy.class));
    }
}
