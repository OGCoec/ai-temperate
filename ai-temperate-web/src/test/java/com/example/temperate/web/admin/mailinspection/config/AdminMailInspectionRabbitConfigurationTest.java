package com.example.temperate.web.admin.mailinspection.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

/**
 * 验证邮箱检查 RabbitMQ 拓扑使用四个独立 Quorum Queue、精确 Direct 路由、有限投递和统一死信边界。
 */
final class AdminMailInspectionRabbitConfigurationTest {

    private final AdminMailInspectionRabbitConfiguration configuration =
            new AdminMailInspectionRabbitConfiguration();

    @Test
    void declaresFourDurableQuorumWorkQueuesWithDeliveryLimitAndDlx() {
        List<Queue> queues = List.of(
                configuration.adminMailInspectionOpenAiQueue(),
                configuration.adminMailInspectionKiroQueue(),
                configuration.adminMailInspectionIp2RegistrationQueue(),
                configuration.adminMailInspectionIp2VerifyQueue());

        assertThat(queues).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments())
                    .containsEntry("x-queue-type", "quorum")
                    .containsEntry("x-delivery-limit", 3)
                    .containsEntry(
                            "x-dead-letter-exchange",
                            MailInspectionRabbitNames.DEAD_EXCHANGE)
                    .containsEntry(
                            "x-dead-letter-routing-key",
                            MailInspectionRabbitNames.DEAD_ROUTING_KEY);
        });
        assertThat(queues).extracting(Queue::getName)
                .doesNotHaveDuplicates();
    }

    @Test
    void bindsEveryWorkQueueToItsExactRoutingKey() {
        DirectExchange exchange =
                configuration.adminMailInspectionWorkExchange();
        Binding openAi = configuration.adminMailInspectionOpenAiBinding(
                configuration.adminMailInspectionOpenAiQueue(),
                exchange);
        Binding kiro = configuration.adminMailInspectionKiroBinding(
                configuration.adminMailInspectionKiroQueue(),
                exchange);
        Binding registration =
                configuration.adminMailInspectionIp2RegistrationBinding(
                        configuration
                                .adminMailInspectionIp2RegistrationQueue(),
                        exchange);
        Binding verify = configuration.adminMailInspectionIp2VerifyBinding(
                configuration.adminMailInspectionIp2VerifyQueue(),
                exchange);

        assertThat(List.of(
                        openAi.getRoutingKey(),
                        kiro.getRoutingKey(),
                        registration.getRoutingKey(),
                        verify.getRoutingKey()))
                .containsExactlyInAnyOrder(
                        MailInspectionRabbitNames.OPENAI_ROUTING_KEY,
                        MailInspectionRabbitNames.KIRO_ROUTING_KEY,
                        MailInspectionRabbitNames
                                .IP2_REGISTRATION_ROUTING_KEY,
                        MailInspectionRabbitNames.IP2_VERIFY_ROUTING_KEY);
    }
}
