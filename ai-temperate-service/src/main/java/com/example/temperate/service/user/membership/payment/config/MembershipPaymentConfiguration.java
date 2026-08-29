package com.example.temperate.service.user.membership.payment.config;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryLoadtestPolicy;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentObservabilityProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 该配置类是来启用会员模拟支付属性绑定，使非法商户配置、批次边界或延时总和在启动阶段失败。
 */
@Configuration
@EnableConfigurationProperties({
        MembershipPaymentProperties.class,
        MembershipPaymentLoadtestProperties.class,
        MembershipPaymentBoundaryLoadtestProperties.class,
        MembershipPaymentObservabilityProperties.class,
        MembershipPaymentRedisWriteProperties.class,
        MembershipPaymentWarmupProperties.class
})
public class MembershipPaymentConfiguration {

    /**
     * 为订单快照微批提供一至六条有界 lane；同一订单由协调器固定路由，线程池本身不决定业务顺序。
     */
    @Bean(name = "membershipPaymentRedisWriteExecutor", destroyMethod = "shutdown")
    @ConditionalOnProperty(
            prefix = "app.membership-payment",
            name = "enabled",
            havingValue = "true")
    ExecutorService membershipPaymentRedisWriteExecutor(
            MembershipPaymentRedisWriteProperties properties) {
        return Executors.newFixedThreadPool(
                properties.laneCount(),
                Thread.ofPlatform()
                        .name("membership-payment-redis-write-", 0L)
                        .factory());
    }

    /** 为 Publisher Confirm 协调器提供八个固定发布 worker；消息可靠性仍由每条 CorrelationData 独立裁决。 */
    @Bean(name = "membershipPaymentRabbitPublishExecutor", destroyMethod = "shutdown")
    @ConditionalOnProperty(
            prefix = "app.membership-payment",
            name = "enabled",
            havingValue = "true")
    ExecutorService membershipPaymentRabbitPublishExecutor() {
        return Executors.newFixedThreadPool(
                8,
                Thread.ofPlatform()
                        .name("membership-payment-rabbit-publish-", 0L)
                        .factory());
    }

    /**
     * 注册固定边界压测策略，用户范围和分组必须由代码常量约束，不能通过外部属性扩大。
     *
     * @return 不可配置的边界压测策略
     */
    @Bean
    MembershipPaymentBoundaryLoadtestPolicy membershipPaymentBoundaryLoadtestPolicy() {
        return new MembershipPaymentBoundaryLoadtestPolicy();
    }
}
