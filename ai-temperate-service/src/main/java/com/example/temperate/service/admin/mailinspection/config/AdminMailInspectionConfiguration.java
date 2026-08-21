package com.example.temperate.service.admin.mailinspection.config;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerPublisher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionPublisher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkPublisher;
import io.netty.channel.ChannelOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

/**
 * 装配管理员邮箱检查专用 ID 编解码、Redis Pub/Sub、HTTP CONNECT 客户端、IMAP 执行器和 Rabbit 发布边界。
 *
 * <p>Redis 监听容器只承载易失通知而不保存任务事实；网络客户端只创建固定 7897 路径，
 * 不读取 local-proxy 候选、不直连且不回退 7892。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AdminMailInspectionProperties.class)
public class AdminMailInspectionConfiguration {

    private static final int RABBIT_PUBLISH_SCHEDULER_QUEUE_CAPACITY = 256;

    @Bean(name = "adminMailInspectionConnectionProvider", destroyMethod = "dispose")
    ConnectionProvider adminMailInspectionConnectionProvider(
            AdminMailInspectionProperties properties) {
        return ConnectionProvider.builder("admin-mail-inspection-oauth")
                .maxConnections(properties.oauth().maxConnections())
                .pendingAcquireMaxCount(properties.oauth().maxConnections() * 4)
                .build();
    }

    @Bean(name = "adminMailInspectionWebClient")
    WebClient adminMailInspectionWebClient(
            WebClient.Builder builder,
            @Qualifier("adminMailInspectionConnectionProvider")
                    ConnectionProvider connectionProvider,
            AdminMailInspectionProperties properties) {
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(
                                properties.oauth().connectTimeout().toMillis()))
                .responseTimeout(properties.oauth().responseTimeout())
                .proxy(proxy -> proxy
                        .type(ProxyProvider.Proxy.HTTP)
                        .host(properties.proxy().host())
                        .port(properties.proxy().port()));
        // clone 防止专用代理和超时污染项目共享 WebClient.Builder。
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean(name = "adminMailInspectionImapExecutor", destroyMethod = "close")
    ExecutorService adminMailInspectionImapExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "adminMailInspectionImapScheduler", destroyMethod = "dispose")
    Scheduler adminMailInspectionImapScheduler(
            @Qualifier("adminMailInspectionImapExecutor")
                    ExecutorService executor) {
        return Schedulers.fromExecutorService(executor);
    }

    /**
     * 隔离 Rabbit 同步发布和 Publisher Confirm 后续信号，防止 AMQP I/O 回调线程重入 Channel 创建流程后等待自身响应。
     *
     * <p>线程上限复用提交发布并发；Confirm 等待本身不占用工作线程，有界排队耗尽时由现有有限发布重试显式收敛。</p>
     */
    @Bean(
            name = "adminMailInspectionRabbitPublishScheduler",
            destroyMethod = "dispose")
    @ConditionalOnProperty(
            prefix = "app.admin.mail-inspection.rabbit",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    Scheduler adminMailInspectionRabbitPublishScheduler(
            AdminMailInspectionProperties properties) {
        return Schedulers.newBoundedElastic(
                properties.submission().publishConcurrency(),
                RABBIT_PUBLISH_SCHEDULER_QUEUE_CAPACITY,
                "admin-mail-rabbit-publish");
    }

    /**
     * 隔离测试显式关闭 Rabbit 时提供 Fail Closed 发布边界，避免自动回退到任何进程内凭证队列。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.admin.mail-inspection.rabbit",
            name = "enabled",
            havingValue = "false")
    MailInspectionWorkPublisher disabledMailInspectionWorkPublisher() {
        return message -> reactor.core.publisher.Mono.error(
                new IllegalStateException(
                        "mail inspection Rabbit is disabled"));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.admin.mail-inspection.rabbit",
            name = "enabled",
            havingValue = "false")
    MailInspectionSubmissionPublisher disabledMailInspectionSubmissionPublisher() {
        return message -> reactor.core.publisher.Mono.error(
                new IllegalStateException(
                        "mail inspection Rabbit is disabled"));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.admin.mail-inspection.rabbit",
            name = "enabled",
            havingValue = "false")
    MailInspectionDispatchMarkerPublisher disabledMailInspectionDispatchMarkerPublisher() {
        return message -> reactor.core.publisher.Mono.error(
                new IllegalStateException(
                        "mail inspection Rabbit is disabled"));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.admin.mail-inspection.rabbit",
            name = "enabled",
            havingValue = "false")
    MailInspectionSubmissionListenerControl disabledMailInspectionSubmissionListenerControl() {
        return new MailInspectionSubmissionListenerControl() {
            @Override
            public reactor.core.publisher.Mono<Void> start(
                    MailInspectionType type) {
                return reactor.core.publisher.Mono.error(
                        new IllegalStateException(
                                "mail inspection Rabbit is disabled"));
            }

            @Override
            public void stop(MailInspectionType type) {
            }

            @Override
            public void stopAll() {
            }
        };
    }

    /**
     * 隔离测试关闭 Rabbit 时拒绝启动消费者，确保禁用配置不会悄悄执行邮箱业务。
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.admin.mail-inspection.rabbit",
            name = "enabled",
            havingValue = "false")
    MailInspectionListenerControl disabledMailInspectionListenerControl() {
        return new MailInspectionListenerControl() {
            @Override
            public reactor.core.publisher.Mono<Void> prepare(
                    MailInspectionType type,
                    int businessConcurrency) {
                return unavailable();
            }

            @Override
            public reactor.core.publisher.Mono<Void> start(
                    MailInspectionType type,
                    int businessConcurrency) {
                return unavailable();
            }

            @Override
            public void stop(MailInspectionType type) {
            }

            @Override
            public void stopAll() {
            }

            private reactor.core.publisher.Mono<Void> unavailable() {
                return reactor.core.publisher.Mono.error(
                        new IllegalStateException(
                                "mail inspection Rabbit is disabled"));
            }
        };
    }
}
