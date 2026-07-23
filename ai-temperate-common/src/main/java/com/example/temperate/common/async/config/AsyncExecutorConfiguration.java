package com.example.temperate.common.async.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 配置应用内异步任务执行器。
 *
 * <p>本类为邮件等非请求关键路径任务提供命名线程池；不负责投递可靠性、任务重试或业务结果确认。</p>
 */
@Configuration
@EnableAsync
public class AsyncExecutorConfiguration {

    @Bean(name = "mailTaskExecutor")
    public Executor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("mail-task-");
        executor.initialize();
        return executor;
    }

}
