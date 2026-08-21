package com.example.temperate.web.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
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

/**
 * 该配置契约测试是来锁定会员支付 YAML 紧邻中文注释以及持久 delayed exchange、Quorum 队列和独立 DLQ。
 */
final class MembershipPaymentConfigurationContractTest {

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
}
