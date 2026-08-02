package com.example.temperate.web.config;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 为 Spring MVC 的阻塞 Servlet 响应写出提供专用有界执行器，并确保外层时限晚于模型流终止时限。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MvcAsyncStreamingProperties.class)
public class MvcAsyncStreamingConfiguration {

    /**
     * 零队列直接交接会让长连接立即取得写出线程或快速拒绝，避免任务在内存队列中等待数分钟。
     *
     * @param properties MVC 异步响应配置
     * @return MVC 异步响应专用执行器
     */
    @Bean(name = "mvcAsyncStreamingTaskExecutor")
    ThreadPoolTaskExecutor mvcAsyncStreamingTaskExecutor(
            MvcAsyncStreamingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mvc-stream-");
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setKeepAliveSeconds(Math.toIntExact(
                properties.keepAlive().toSeconds()));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    /**
     * 显式覆盖 Spring MVC 默认异步执行器，使响应写出速度能够通过订阅需求反压到模型 Flux。
     *
     * @param executor MVC 流式写出执行器
     * @param properties MVC 异步响应配置
     * @return 只负责异步响应边界的 MVC 配置器
     */
    @Bean
    WebMvcConfigurer mvcAsyncStreamingConfigurer(
            @Qualifier("mvcAsyncStreamingTaskExecutor")
            AsyncTaskExecutor executor,
            MvcAsyncStreamingProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(
                    AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(executor);
                configurer.setDefaultTimeout(properties.timeout().toMillis());
            }
        };
    }

    /**
     * 外层 Servlet 超时必须晚于模型总期限，否则浏览器会先断开，退款和受控 SSE 错误无法完成。
     *
     * @param mvcProperties MVC 异步响应配置
     * @param inferenceProperties 模型推理总时限配置
     * @return Spring 单例装配完成后的超时关系校验器
     */
    @Bean
    SmartInitializingSingleton mvcAsyncStreamingTimeoutValidator(
            MvcAsyncStreamingProperties mvcProperties,
            AiInferenceProperties inferenceProperties) {
        return () -> {
            if (mvcProperties.timeout().compareTo(
                    inferenceProperties.maxStreamDuration()) <= 0) {
                throw new IllegalStateException(
                        "MVC async timeout must be greater than AI maximum stream duration.");
            }
        };
    }
}
