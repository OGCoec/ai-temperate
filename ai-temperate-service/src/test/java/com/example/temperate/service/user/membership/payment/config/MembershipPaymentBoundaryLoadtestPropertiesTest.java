package com.example.temperate.service.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 用于验证四千用户边界夹具只有一个默认关闭的布尔开关，不携带可扩张的用户范围或套餐配置。
 */
class MembershipPaymentBoundaryLoadtestPropertiesTest {

    @Test
    void shouldRemainDisabledUnlessExplicitlyEnabled() {
        assertThat(new MembershipPaymentBoundaryLoadtestProperties(false).enabled()).isFalse();
        assertThat(new MembershipPaymentBoundaryLoadtestProperties(true).enabled()).isTrue();
        assertThat(MembershipPaymentBoundaryLoadtestProperties.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("enabled");
    }
}
