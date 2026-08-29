package com.example.temperate.service.user.membership.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 约束四千用户毫秒边界夹具的独立启用开关，不允许通过配置扩张固定用户、分组或套餐范围。
 *
 * @param enabled 是否显式启用只供回环真实时间压测使用的边界夹具
 */
@ConfigurationProperties(prefix = "app.membership-payment.boundary-loadtest")
public record MembershipPaymentBoundaryLoadtestProperties(@DefaultValue("false") boolean enabled) {
}
