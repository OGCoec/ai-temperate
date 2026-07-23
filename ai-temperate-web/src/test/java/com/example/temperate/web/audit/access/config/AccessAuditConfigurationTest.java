package com.example.temperate.web.audit.access.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.audit.access.config.AccessAuditProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证访问审计 RabbitMQ 批量消费者的等待、预取和确认配置，防止低流量批次长期占用未确认窗口。
 */
class AccessAuditConfigurationTest {

    @Test
    void configuresAnAbsoluteBatchReceiveTimeoutForPartialBatches() {
        AccessAuditConfiguration configuration = new AccessAuditConfiguration();
        AccessAuditProperties properties = new AccessAuditProperties(
                true,
                "test",
                Duration.ofDays(30),
                200,
                Duration.ofMillis(500),
                15,
                1000,
                50);

        SimpleRabbitListenerContainerFactory factory =
                configuration.accessAuditRabbitListenerContainerFactory(
                        new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()),
                        mock(ConnectionFactory.class),
                        configuration.accessAuditMessageConverter(),
                        properties);

        assertThat(ReflectionTestUtils.getField(factory, "consumerBatchEnabled"))
                .isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(factory, "batchListener"))
                .isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(factory, "batchSize"))
                .isEqualTo(200);
        assertThat(ReflectionTestUtils.getField(factory, "prefetchCount"))
                .isEqualTo(200);
        assertThat(factory.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(factory, "defaultRequeueRejected"))
                .isEqualTo(false);
        assertThat(ReflectionTestUtils.getField(factory, "receiveTimeout"))
                .isEqualTo(500L);
        assertThat(ReflectionTestUtils.getField(factory, "batchReceiveTimeout"))
                .isEqualTo(500L);
    }
}
