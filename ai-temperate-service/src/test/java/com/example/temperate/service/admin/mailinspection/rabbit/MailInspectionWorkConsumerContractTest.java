package com.example.temperate.service.admin.mailinspection.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionFailureStage;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.InMemoryAdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import com.example.temperate.service.admin.mailinspection.strategy.MailInspectionStrategy;
import com.example.temperate.service.admin.mailinspection.strategy.MailInspectionStrategyRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import reactor.core.publisher.Mono;

/**
 * 验证四个邮件工作监听入口默认保持停止，并通过异步 Mono 完成信号把 ACK 时机交给 Spring AMQP。
 */
final class MailInspectionWorkConsumerContractTest {

    @Test
    void declaresFourStoppedAsyncListenerEntrypoints() {
        Method[] listeners = Arrays.stream(
                        MailInspectionWorkConsumer.class
                                .getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(
                        RabbitListener.class))
                .toArray(Method[]::new);

        assertThat(listeners).hasSize(4);
        assertThat(listeners).allSatisfy(method -> {
            RabbitListener annotation =
                    method.getAnnotation(RabbitListener.class);
            assertThat(annotation.autoStartup()).isEqualTo("false");
            assertThat(method.getReturnType()).isEqualTo(Mono.class);
        });
    }

    @Test
    void acceptsPositiveLineNumbersAboveOneHundredAtEnvelopeBoundary() {
        PublicIdCodec codec = new PublicIdCodec();
        MailInspectionWorkConsumer consumer = new MailInspectionWorkConsumer(
                mock(AdminMailInspectionJobStore.class),
                mock(MailInspectionStrategyRegistry.class),
                mock(AdminMailInspectionPayloadProtector.class),
                codec,
                AdminMailInspectionProperties.defaults(),
                Clock.systemUTC());
        MailInspectionWorkMessage message = new MailInspectionWorkMessage(
                "message-1000",
                MailInspectionRabbitNames.EVENT_TYPE,
                MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION,
                Instant.parse("2026-07-28T12:00:00Z"),
                "trace-1000",
                1L,
                codec.encode(1L),
                MailInspectionType.OPENAI_STATUS,
                1_000,
                1_000,
                1_000,
                0,
                0,
                4,
                Instant.parse("2026-07-28T12:00:00Z"),
                new MailInspectionProtectedPayload("iv", "ciphertext"));

        assertThatThrownBy(() -> consumer.consumeOpenAi(message).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job state unavailable");
    }

    @Test
    void acknowledgesDuplicateDeliveryAfterResultWasAlreadyCompleted() {
        AdminMailInspectionProperties properties =
                AdminMailInspectionProperties.defaults();
        Instant now = Instant.parse("2026-07-28T12:00:00Z");
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        PublicIdCodec codec = new PublicIdCodec();
        long internalId = 7L;
        String publicId = codec.encode(internalId);
        MailInspectionJobState state = MailInspectionJobState.recovered(
                internalId,
                publicId,
                MailInspectionType.OPENAI_STATUS,
                1,
                1,
                0,
                0,
                4,
                now,
                now,
                List.of(new MailInspectionPendingItem(
                        1,
                        "u***@example.test")));
        assertThat(state.markRunning(now)).isTrue();
        InMemoryAdminMailInspectionJobStore store =
                new InMemoryAdminMailInspectionJobStore(properties, clock);
        store.startAccepting(MailInspectionType.OPENAI_STATUS);
        store.create(state);

        AtomicInteger inspections = new AtomicInteger();
        MailInspectionStrategy strategy = new MailInspectionStrategy() {
            @Override
            public MailInspectionType type() {
                return MailInspectionType.OPENAI_STATUS;
            }

            @Override
            public Mono<MailInspectionResult> inspect(
                    MailboxCredential credential) {
                inspections.incrementAndGet();
                return Mono.just(new MailInspectionResult(
                        credential.lineNumber(),
                        credential.email(),
                        MailInspectionResultStatus
                                .OPENAI_NO_REGISTRATION_EVIDENCE,
                        MailInspectionFailureStage.BUSINESS,
                        "test-no-evidence",
                        1,
                        1,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
            }
        };
        AdminMailInspectionPayloadProtector protector =
                new AdminMailInspectionPayloadProtector(properties);
        String messageId = "message-duplicate";
        MailboxCredential credential = new MailboxCredential(
                1,
                "user@example.test",
                "11111111-1111-1111-1111-111111111111",
                "refresh-token");
        MailInspectionWorkMessage message = new MailInspectionWorkMessage(
                messageId,
                MailInspectionRabbitNames.EVENT_TYPE,
                MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION,
                now,
                "trace-duplicate",
                internalId,
                publicId,
                MailInspectionType.OPENAI_STATUS,
                1,
                1,
                1,
                0,
                0,
                4,
                now,
                protector.protect(
                        messageId,
                        publicId,
                        MailInspectionType.OPENAI_STATUS,
                        credential));
        MailInspectionWorkConsumer consumer = new MailInspectionWorkConsumer(
                store,
                new MailInspectionStrategyRegistry(
                        Map.of("testOpenAiStrategy", strategy)),
                protector,
                codec,
                properties,
                clock);

        consumer.consumeOpenAi(message).block();
        consumer.consumeOpenAi(message).block();

        assertThat(inspections).hasValue(1);
        assertThat(state.snapshot().processedCount()).isEqualTo(1);
    }
}
