package com.example.temperate.web.admin.mailinspection.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证邮件检查监听容器能够在不连接 RabbitMQ 的情况下锁定每个任务要求的固定业务并发。
 */
final class RabbitMailInspectionListenerControlTest {

    @Test
    void configuresAllSupportedPresetConcurrencyValues() {
        int[] supportedValues = {1, 4, 8, 16, 32, 64};

        for (int target : supportedValues) {
            SimpleMessageListenerContainer container =
                    new SimpleMessageListenerContainer();

            assertThatCode(() -> RabbitMailInspectionListenerControl
                    .configureConcurrency(container, target))
                    .doesNotThrowAnyException();
            assertFixedConcurrency(container, target);
        }
    }

    @Test
    void reconfiguresSameContainerAcrossHigherAndLowerTargets() {
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer();
        int[] transitions = {1, 64, 4, 32, 1};

        for (int target : transitions) {
            RabbitMailInspectionListenerControl.configureConcurrency(
                    container,
                    target);
            assertFixedConcurrency(container, target);
        }
    }

    @Test
    void rejectsConcurrencyOutsideSupportedRange() {
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer();

        for (int invalid : new int[] {-1, 0, 65}) {
            assertThatThrownBy(() -> RabbitMailInspectionListenerControl
                    .configureConcurrency(container, invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "business concurrency must be between 1 and 64");
        }
    }

    private static void assertFixedConcurrency(
            SimpleMessageListenerContainer container,
            int expected) {
        assertThat(ReflectionTestUtils.getField(
                container,
                "concurrentConsumers"))
                .isEqualTo(expected);
        assertThat(ReflectionTestUtils.getField(
                container,
                "maxConcurrentConsumers"))
                .isEqualTo(expected);
    }
}
