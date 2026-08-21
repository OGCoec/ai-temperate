package com.example.temperate.service.user.membership.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 该配置类是来启用会员模拟支付属性绑定，使非法商户配置、批次边界或延时总和在启动阶段失败。
 */
@Configuration
@EnableConfigurationProperties({
        MembershipPaymentProperties.class,
        MembershipPaymentLoadtestProperties.class
})
public class MembershipPaymentConfiguration {
}
