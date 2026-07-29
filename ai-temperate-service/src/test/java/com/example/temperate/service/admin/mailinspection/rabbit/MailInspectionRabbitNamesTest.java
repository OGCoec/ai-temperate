package com.example.temperate.service.admin.mailinspection.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * 验证管理员邮箱检查的四条 RabbitMQ 精确路由彼此隔离，并且每种检查类型都只映射到一个工作队列。
 */
final class MailInspectionRabbitNamesTest {

    @Test
    void mapsEveryInspectionTypeToOneUniqueQueueAndRoutingKey() {
        assertThat(MailInspectionRabbitNames.supportedTypes())
                .containsExactlyInAnyOrderElementsOf(
                        EnumSet.allOf(MailInspectionType.class));
        assertThat(MailInspectionRabbitNames.supportedTypes().stream()
                .map(MailInspectionRabbitNames::queue)
                .distinct())
                .hasSize(4);
        assertThat(MailInspectionRabbitNames.supportedTypes().stream()
                .map(MailInspectionRabbitNames::routingKey)
                .distinct())
                .hasSize(4);
    }

    @Test
    void keepsDirectExchangeAndDeadLetterNamesStable() {
        assertThat(MailInspectionRabbitNames.WORK_EXCHANGE)
                .isEqualTo("ait.admin.mail-inspection.work.v2");
        assertThat(MailInspectionRabbitNames.DEAD_EXCHANGE)
                .isEqualTo("ait.admin.mail-inspection.dead.v2");
        assertThat(MailInspectionRabbitNames.DEAD_QUEUE)
                .isEqualTo("ait.admin.mail-inspection.dead.v2");
        assertThat(MailInspectionRabbitNames.DEAD_ROUTING_KEY)
                .isEqualTo("mail-inspection.dead");
    }
}
