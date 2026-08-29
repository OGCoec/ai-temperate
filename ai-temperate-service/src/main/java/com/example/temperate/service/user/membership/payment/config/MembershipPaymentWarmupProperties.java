package com.example.temperate.service.user.membership.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 该配置是来控制会员支付 Redis 与 RabbitMQ 的无业务副作用启动预热，以及预热失败时是否阻止实例进入可用状态。
 */
@ConfigurationProperties(prefix = "app.membership-payment.warmup")
public record MembershipPaymentWarmupProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean failFast) {
}
