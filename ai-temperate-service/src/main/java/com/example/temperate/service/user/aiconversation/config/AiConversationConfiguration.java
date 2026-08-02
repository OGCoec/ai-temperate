package com.example.temperate.service.user.aiconversation.config;

import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import java.util.Base64;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 装配 AI 会话配置、幂等摘要器和有界压缩执行器，避免业务实现自行读取环境变量或创建无界线程。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AiConversationProperties.class,
        AiConversationLifecycleDiagnosticsProperties.class,
        AiConversationStreamDiagnosticsProperties.class,
        AiConversationAsyncGenerationProperties.class,
        AiConversationAttachmentProperties.class,
        AiConversationSecurityProperties.class,
        AiInferenceProperties.class
})
public class AiConversationConfiguration {

    /**
     * 使用通过启动校验的独立密钥创建幂等摘要器，不把密钥对象暴露给其他业务域。
     *
     * @param properties AI 会话安全配置
     * @return 幂等摘要器
     */
    @Bean
    AiConversationIdempotencyHasher aiConversationIdempotencyHasher(
            AiConversationSecurityProperties properties) {
        return new AiConversationIdempotencyHasher(
                Base64.getDecoder().decode(
                        properties.idempotencyHmacKeyBase64()));
    }

    /**
     * 压缩任务只用于可重试的派生数据，使用小型有界队列防止模型或上游变慢时拖垮请求线程。
     *
     * @return AI 会话压缩执行器
     */
    @Bean
    @Qualifier("aiConversationCompactionExecutor")
    Executor aiConversationCompactionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-conversation-compact-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 客户端取消结算和系统失败退款必须离开 Reactor 取消线程，同时使用有界队列避免断连风暴创建无界任务。
     *
     * @return AI 会话终态结算与退款执行器
     */
    @Bean
    @Qualifier("aiConversationFinalizerExecutor")
    Executor aiConversationFinalizerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-conversation-finalize-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(128);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 推理关闭时不要求创建模型客户端；显式启用后则在接收请求前验证 Spring AI 边界完整。
     *
     * @param properties 推理配置
     * @param chatModelProvider Spring AI 普通 OpenAI 模型提供器
     * @return 启动末期校验器
     */
    @Bean
    SmartInitializingSingleton aiInferenceStartupValidator(
            AiInferenceProperties properties,
            ObjectProvider<OpenAiChatModel> chatModelProvider) {
        return () -> {
            if (properties.enabled()
                    && chatModelProvider.getIfAvailable() == null) {
                throw new IllegalStateException(
                        "Enabled AI inference requires an OpenAiChatModel bean.");
            }
        };
    }
}
