package com.example.temperate.service.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.config.AiConversationLifecycleDiagnosticsProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.impl.AiConversationLifecycleDiagnosticServiceImpl;
import com.example.temperate.service.user.aiconversation.diagnostic.impl.NoOpAiConversationLifecycleDiagnosticServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 验证生命周期诊断开关在启动期只选择一个实现，并且关闭时不注册 AOP 切面。
 */
final class AiConversationLifecycleConditionalBeansTest {

    @Test
    void disabledConfigurationSelectsOnlyNoOpService() {
        runner(false)
                .withPropertyValues(
                        "app.ai-conversation.lifecycle-diagnostics.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AiConversationLifecycleDiagnosticService.class);
                    assertThat(context).hasSingleBean(
                            NoOpAiConversationLifecycleDiagnosticServiceImpl.class);
                    assertThat(context).doesNotHaveBean(
                            AiConversationLifecycleTimingAspect.class);
                });
    }

    @Test
    void enabledConfigurationSelectsLoggerAndAspect() {
        runner(true)
                .withPropertyValues(
                        "app.ai-conversation.lifecycle-diagnostics.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AiConversationLifecycleDiagnosticService.class);
                    assertThat(context).hasSingleBean(
                            AiConversationLifecycleDiagnosticServiceImpl.class);
                    assertThat(context).hasSingleBean(
                            AiConversationLifecycleTimingAspect.class);
                });
    }

    private static ApplicationContextRunner runner(boolean enabled) {
        return new ApplicationContextRunner()
                .withBean(
                        AiConversationLifecycleDiagnosticsProperties.class,
                        () -> new AiConversationLifecycleDiagnosticsProperties(
                                enabled, enabled ? 1.0d : 0.0d))
                .withUserConfiguration(LifecycleConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            AiConversationLifecycleDiagnosticServiceImpl.class,
            NoOpAiConversationLifecycleDiagnosticServiceImpl.class,
            AiConversationLifecycleTimingAspect.class
    })
    static class LifecycleConfiguration {
    }
}
