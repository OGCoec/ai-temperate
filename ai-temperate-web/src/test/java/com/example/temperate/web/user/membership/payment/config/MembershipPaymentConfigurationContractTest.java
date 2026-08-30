package com.example.temperate.web.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 该配置契约测试是来锁定会员支付 YAML 紧邻中文注释以及持久 delayed exchange、Quorum 队列和独立 DLQ。
 */
final class MembershipPaymentConfigurationContractTest {

    @Test
    void productionDefaultsUseTheApprovedSingleInstanceCapacity() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        assertThat(resolver.getProperty(
                "spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(256);
        assertThat(resolver.getProperty(
                "spring.datasource.hikari.minimum-idle", Integer.class)).isEqualTo(8);
        assertThat(resolver.getProperty("server.tomcat.accept-count", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("server.tomcat.max-connections", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("server.tomcat.threads.max", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("spring.rabbitmq.requested-channel-max", Integer.class))
                .isEqualTo(512);
        assertThat(resolver.getProperty("spring.rabbitmq.cache.channel.size", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("spring.rabbitmq.cache.channel.checkout-timeout"))
                .isEqualTo("30s");
    }

    @Test
    void everyMembershipPaymentYamlLineHasAnAdjacentChineseComment()
            throws IOException {
        List<String> lines = Files.readAllLines(
                Path.of("src/main/resources/application.yml"),
                StandardCharsets.UTF_8);
        int start = lines.indexOf("  membership-payment:");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = start + 1;
        while (end < lines.size()
                && (lines.get(end).isBlank()
                        || lines.get(end).startsWith("    ")
                        || lines.get(end).trim().startsWith("#"))) {
            end++;
        }
        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            assertThat(lines.get(index - 1).trim())
                    .as("会员支付配置第 %s 行必须有紧邻中文注释", index + 1)
                    .startsWith("#")
                    .matches(".*[\\u4e00-\\u9fff].*");
        }
    }

    @Test
    void topologyUsesDurableDelayedExchangesAndQuorumQueues() {
        Declarables declarables =
                new MembershipPaymentRabbitConfiguration()
                        .membershipPaymentRabbitTopology();
        Collection<CustomExchange> exchanges =
                declarables.getDeclarablesByType(CustomExchange.class);
        Collection<DirectExchange> deadLetterExchanges =
                declarables.getDeclarablesByType(DirectExchange.class);
        Collection<Queue> queues = declarables.getDeclarablesByType(Queue.class);

        assertThat(exchanges)
                .hasSize(2)
                .allSatisfy(exchange -> {
                    assertThat(exchange.isDurable()).isTrue();
                    assertThat(exchange.getType()).isEqualTo("x-delayed-message");
                });
        assertThat(deadLetterExchanges)
                .hasSize(2)
                .allSatisfy(exchange -> assertThat(exchange.isDurable()).isTrue());
        assertThat(queues)
                .extracting(Queue::getName)
                .containsExactlyInAnyOrder(
                        MembershipPaymentRabbitNames.PAYMENT_QUEUE,
                        MembershipPaymentRabbitNames.PAYMENT_DLQ,
                        MembershipPaymentRabbitNames.CLOSING_QUEUE,
                        MembershipPaymentRabbitNames.CLOSING_DLQ);
        assertThat(queues).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
        });
    }

    @Test
    void membershipQueuesEachUseFortyEightConsumersAndPrefetchTwenty() {
        MembershipPaymentRabbitConfiguration configuration =
                new MembershipPaymentRabbitConfiguration();
        SimpleRabbitListenerContainerFactory factory =
                configuration.membershipPaymentListenerContainerFactory(
                        new SimpleRabbitListenerContainerFactoryConfigurer(
                                new RabbitProperties()),
                        mock(ConnectionFactory.class),
                        configuration.membershipPaymentMessageConverter(
                                new ObjectMapper()));

        assertThat(ReflectionTestUtils.getField(factory, "concurrentConsumers"))
                .isEqualTo(48);
        assertThat(ReflectionTestUtils.getField(factory, "maxConcurrentConsumers"))
                .isEqualTo(48);
        assertThat(ReflectionTestUtils.getField(factory, "prefetchCount"))
                .isEqualTo(20);
    }

    @Test
    void requestedChannelMaxIsAppliedToRabbitJavaClient() {
        MembershipPaymentRabbitConfiguration configuration =
                new MembershipPaymentRabbitConfiguration();
        com.rabbitmq.client.ConnectionFactory rabbitClient =
                new com.rabbitmq.client.ConnectionFactory();

        ConnectionFactoryCustomizer customizer =
                configuration.membershipPaymentConnectionFactoryCustomizer(16_384);
        customizer.customize(rabbitClient);

        assertThat(rabbitClient.getRequestedChannelMax()).isEqualTo(16_384);
    }
}
