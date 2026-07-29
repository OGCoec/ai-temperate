package com.example.temperate.service.admin.mailinspection.recovery.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryObserver;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用隔离 RabbitMQ 验证多个历史 Marker 可被物理恢复会话清理，失败会把 Unacked 归还为 Ready。
 *
 * <p>本测试只访问 Testcontainers 临时 Broker，不连接当前开发或生产 RabbitMQ，也不执行 OAuth、IMAP 或业务策略。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
final class MailInspectionRecoveryIsolationIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T06:00:00Z");

    @Container
    private static final GenericContainer<?> RABBIT =
            new GenericContainer<>(
                    DockerImageName.parse("rabbitmq:4.2-management"))
                    .withEnv("RABBITMQ_DEFAULT_USER", "mail-recovery-test")
                    .withEnv(
                            "RABBITMQ_DEFAULT_PASS",
                            "mail-recovery-test-password")
                    .withExposedPorts(5672);

    private CachingConnectionFactory springConnectionFactory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetQueues() {
        springConnectionFactory = new CachingConnectionFactory(
                RABBIT.getHost(),
                RABBIT.getMappedPort(5672));
        springConnectionFactory.setUsername("mail-recovery-test");
        springConnectionFactory.setPassword(
                "mail-recovery-test-password");
        objectMapper = new ObjectMapper().findAndRegisterModules();
        RabbitAdmin admin = new RabbitAdmin(springConnectionFactory);
        for (MailInspectionType type :
                MailInspectionRabbitNames.supportedTypes()) {
            recreate(admin, MailInspectionRabbitNames.submissionQueue(type));
            recreate(
                    admin,
                    MailInspectionRabbitNames.dispatchStateQueue(type));
            recreate(admin, MailInspectionRabbitNames.queue(type));
        }
    }

    @AfterEach
    void closeSpringConnectionFactory() {
        springConnectionFactory.destroy();
    }

    @Test
    void clearsTwoHistoricalMarkerJobsAndOpensTheirType()
            throws Exception {
        PublicIdCodec publicIdCodec = new PublicIdCodec();
        publish(
                MailInspectionRabbitNames.OPENAI_DISPATCH_STATE_QUEUE,
                marker(publicIdCodec, 51L, "A".repeat(43)));
        publish(
                MailInspectionRabbitNames.OPENAI_DISPATCH_STATE_QUEUE,
                marker(publicIdCodec, 52L, "B".repeat(43)));
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);

        coordinator(jobStore, publicIdCodec).recoverAll().block();

        assertThat(readyCount(
                MailInspectionRabbitNames.OPENAI_DISPATCH_STATE_QUEUE))
                .isZero();
        verify(jobStore).startAccepting(
                MailInspectionType.OPENAI_STATUS);
    }

    @Test
    void malformedMarkerReturnsToReadyAndDoesNotBlockOtherTypes()
            throws Exception {
        publishRaw(
                MailInspectionRabbitNames
                        .IP2_VERIFY_DISPATCH_STATE_QUEUE,
                "{invalid-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);

        coordinator(jobStore, new PublicIdCodec()).recoverAll().block();

        assertThat(readyCount(
                MailInspectionRabbitNames
                        .IP2_VERIFY_DISPATCH_STATE_QUEUE))
                .isEqualTo(1);
        verify(jobStore).markUnavailable(
                MailInspectionType.IP2LOCATION_VERIFY_LINK,
                "RECOVERY_MESSAGE_DESERIALIZE");
        verify(jobStore).startAccepting(
                MailInspectionType.OPENAI_STATUS);
        verify(jobStore).startAccepting(
                MailInspectionType.KIRO_STATUS);
    }

    private MailInspectionRecoveryCoordinatorImpl coordinator(
            AdminMailInspectionJobStore jobStore,
            PublicIdCodec publicIdCodec) {
        return new MailInspectionRecoveryCoordinatorImpl(
                new MailInspectionRecoveryConnectionFactoryImpl(
                        springConnectionFactory),
                new MailInspectionRecoveryPlanner(),
                new MailInspectionTypeLifecycleGuard(),
                mock(MailInspectionRecoveryObserver.class),
                objectMapper,
                mock(AdminMailInspectionPayloadProtector.class),
                mock(AdminMailInspectionSubmissionPayloadProtector.class),
                jobStore,
                publicIdCodec,
                AdminMailInspectionProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void publish(
            String queue,
            MailInspectionDispatchMarkerMessage marker) throws Exception {
        publishRaw(queue, objectMapper.writeValueAsBytes(marker));
    }

    private void publishRaw(
            String queue,
            byte[] body) throws Exception {
        try (Connection connection = springConnectionFactory
                        .getRabbitConnectionFactory()
                        .newConnection("mail-recovery-test-publisher");
                Channel channel = connection.createChannel()) {
            channel.basicPublish(
                    "",
                    queue,
                    new AMQP.BasicProperties.Builder()
                            .contentType("application/json")
                            .deliveryMode(2)
                            .build(),
                    body);
        }
    }

    private int readyCount(String queue) throws Exception {
        try (Connection connection = springConnectionFactory
                        .getRabbitConnectionFactory()
                        .newConnection("mail-recovery-test-observer");
                Channel channel = connection.createChannel()) {
            return channel.queueDeclarePassive(queue).getMessageCount();
        }
    }

    private static void recreate(
            RabbitAdmin admin,
            String queueName) {
        admin.deleteQueue(queueName);
        admin.declareQueue(new Queue(
                queueName,
                true,
                false,
                false));
    }

    private static MailInspectionDispatchMarkerMessage marker(
            PublicIdCodec publicIdCodec,
            long internalId,
            String fingerprint) {
        return new MailInspectionDispatchMarkerMessage(
                "marker-" + internalId,
                MailInspectionRabbitNames.DISPATCH_MARKER_EVENT_TYPE,
                MailInspectionRabbitNames.DISPATCH_MARKER_SCHEMA_VERSION,
                NOW,
                "trace-test",
                "550e8400-e29b-41d4-a716-44665544"
                        + String.format("%04d", internalId),
                fingerprint,
                internalId,
                publicIdCodec.encode(internalId),
                MailInspectionType.OPENAI_STATUS,
                0,
                1,
                4,
                NOW,
                NOW);
    }
}
