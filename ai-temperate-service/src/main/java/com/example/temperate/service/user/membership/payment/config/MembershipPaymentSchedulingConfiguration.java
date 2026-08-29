package com.example.temperate.service.user.membership.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 该配置类是来隔离会员支付回调与订单刷盘调度线程，避免共享默认调度器上的其他定时任务放大支付收敛延迟。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public class MembershipPaymentSchedulingConfiguration {

    public static final String CALLBACK_TASK_SCHEDULER =
            "membershipPaymentCallbackTaskScheduler";
    public static final String ORDER_PERSIST_TASK_SCHEDULER =
            "membershipPaymentOrderPersistTaskScheduler";

    /**
     * 回调收敛固定单线程执行，保证同一实例不会并发领取同一批工作，同时不再受其他定时任务阻塞。
     */
    @Bean(name = CALLBACK_TASK_SCHEDULER)
    ThreadPoolTaskScheduler membershipPaymentCallbackTaskScheduler() {
        return newSingleThreadScheduler("membership-payment-callback-");
    }

    /**
     * 订单刷盘使用另一条固定单线程，避免长数据库批次阻塞回调收敛，也保持同一脏版本队列顺序处理。
     */
    @Bean(name = ORDER_PERSIST_TASK_SCHEDULER)
    ThreadPoolTaskScheduler membershipPaymentOrderPersistTaskScheduler() {
        return newSingleThreadScheduler("membership-payment-order-persist-");
    }

    private static ThreadPoolTaskScheduler newSingleThreadScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
