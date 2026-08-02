package com.example.temperate.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import java.time.Duration;
import java.util.concurrent.SynchronousQueue;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 验证 MVC 流式响应使用零队列有界执行器，并且外层异步时限严格晚于模型总时限。
 */
final class MvcAsyncStreamingConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MvcAsyncStreamingConfiguration.class)
                    .withBean(AiInferenceProperties.class, () ->
                            new AiInferenceProperties(
                                    false,
                                    "https://cli-proxy.example.test/v1",
                                    "",
                                    Duration.ofMinutes(15)))
                    .withPropertyValues(
                            "app.web.mvc-async.core-pool-size=16",
                            "app.web.mvc-async.max-pool-size=160",
                            "app.web.mvc-async.queue-capacity=0",
                            "app.web.mvc-async.keep-alive=60s",
                            "app.web.mvc-async.timeout=16m");

    @Test
    void registersBoundedZeroQueueExecutorAndMvcConfigurer() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                    "mvcAsyncStreamingTaskExecutor",
                    ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(16);
            assertThat(executor.getMaxPoolSize()).isEqualTo(160);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(60);
            assertThat(executor.getThreadPoolExecutor().getQueue())
                    .isInstanceOf(SynchronousQueue.class);
            assertThat(executor.getThreadPoolExecutor()
                            .allowsCoreThreadTimeOut())
                    .isTrue();
            assertThat(context.getBeansOfType(WebMvcConfigurer.class))
                    .containsKey("mvcAsyncStreamingConfigurer");
        });
    }

    @Test
    void rejectsMvcTimeoutThatCannotOutliveModelStream() {
        contextRunner
                .withPropertyValues("app.web.mvc-async.timeout=15m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage(
                                    "MVC async timeout must be greater than AI maximum stream duration.");
                });
    }
}
