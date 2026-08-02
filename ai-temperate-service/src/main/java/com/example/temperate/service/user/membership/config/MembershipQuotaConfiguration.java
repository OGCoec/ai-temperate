package com.example.temperate.service.user.membership.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用会员额度计划属性绑定，使缺档或非法额度在应用启动阶段直接失败。
 */
@Configuration
@EnableConfigurationProperties(MembershipQuotaProperties.class)
public class MembershipQuotaConfiguration {
}
