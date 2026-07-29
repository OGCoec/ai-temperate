package com.example.temperate.service.admin.mailinspection.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.InMemoryAdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.parser.MailboxCredentialParser;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunker;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionPublisher;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import com.example.temperate.service.admin.mailinspection.security.impl.MailInspectionRequestFingerprinterImpl;
import com.example.temperate.service.admin.mailinspection.strategy.MailInspectionStrategy;
import com.example.temperate.service.admin.mailinspection.strategy.MailInspectionStrategyRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证创建 Service 只持久确认 Submission Chunk，并以相同幂等键复用同一个任务。
 */
final class AdminMailInspectionJobServiceImplTest {

    @Test
    void confirmsSubmissionAndReplaysSameJob() {
        AdminMailInspectionProperties properties =
                AdminMailInspectionProperties.defaults();
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-28T10:00:00Z"),
                ZoneOffset.UTC);
        SnowflakeIdWorker ids = mock(SnowflakeIdWorker.class);
        when(ids.nextId()).thenReturn(9L, 10L);
        MailInspectionStrategy strategy = new MailInspectionStrategy() {
            @Override
            public MailInspectionType type() {
                return MailInspectionType.OPENAI_STATUS;
            }

            @Override
            public Mono<MailInspectionResult> inspect(
                    com.example.temperate.service.admin.mailinspection.domain
                                    .MailboxCredential credential) {
                return Mono.empty();
            }
        };
        InMemoryAdminMailInspectionJobStore store =
                new InMemoryAdminMailInspectionJobStore(properties, clock);
        store.startAccepting(MailInspectionType.OPENAI_STATUS);
        RecordingSubmissionControl submissionControl =
                new RecordingSubmissionControl();
        AdminMailInspectionSubmissionPayloadProtector protector =
                new AdminMailInspectionSubmissionPayloadProtector(properties);
        MailInspectionSubmissionMessageFactory messageFactory =
                new MailInspectionSubmissionMessageFactory(
                        new MailInspectionSubmissionChunker(properties),
                        protector,
                        clock);
        MailInspectionSubmissionPublisher publisher = message -> Mono.empty();
        AdminMailInspectionJobServiceImpl service =
                new AdminMailInspectionJobServiceImpl(
                        new MailboxCredentialParser(properties),
                        new MailInspectionStrategyRegistry(
                                Map.of("openai", strategy)),
                        store,
                        new MailInspectionRequestFingerprinterImpl(properties),
                        messageFactory,
                        publisher,
                        submissionControl,
                        new NoopWorkListenerControl(),
                        ids,
                        new PublicIdCodec(),
                        properties,
                        clock,
                        new MailInspectionTypeLifecycleGuard());
        AdminMailInspectionCreateCommand command =
                new AdminMailInspectionCreateCommand(
                        "550e8400-e29b-41d4-a716-446655440000",
                        List.of(
                                "owner@example.test----unused----"
                                        + "11111111-1111-1111-1111-111111111111----refresh"),
                        32);

        StepVerifier.create(service.create(
                        MailInspectionType.OPENAI_STATUS, command))
                .assertNext(created -> {
                    assertThat(created.jobId())
                            .isEqualTo(new PublicIdCodec().encode(9L));
                    assertThat(created.idempotencyReplayed()).isFalse();
                    assertThat(created.confirmedSubmissionChunkCount())
                            .isEqualTo(1);
                })
                .verifyComplete();

        StepVerifier.create(service.create(
                        MailInspectionType.OPENAI_STATUS, command))
                .assertNext(replayed -> {
                    assertThat(replayed.jobId())
                            .isEqualTo(new PublicIdCodec().encode(9L));
                    assertThat(replayed.idempotencyReplayed()).isTrue();
                })
                .verifyComplete();

        assertThat(service.get(9L).status())
                .isEqualTo(MailInspectionJobStatus.DISPATCHING);
        assertThat(submissionControl.started).isEqualTo(2);
    }

    @Test
    void partialSubmissionRetryReusesOriginalJobAndCompletesMissingChunk() {
        AdminMailInspectionProperties properties =
                AdminMailInspectionProperties.defaults();
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-28T10:00:00Z"),
                ZoneOffset.UTC);
        SnowflakeIdWorker ids = mock(SnowflakeIdWorker.class);
        when(ids.nextId()).thenReturn(9L, 10L);
        MailInspectionStrategy typedStrategy = new MailInspectionStrategy() {
            @Override
            public MailInspectionType type() {
                return MailInspectionType.OPENAI_STATUS;
            }

            @Override
            public Mono<MailInspectionResult> inspect(
                    com.example.temperate.service.admin.mailinspection.domain
                                    .MailboxCredential credential) {
                return Mono.empty();
            }
        };
        InMemoryAdminMailInspectionJobStore store =
                new InMemoryAdminMailInspectionJobStore(properties, clock);
        store.startAccepting(MailInspectionType.OPENAI_STATUS);
        AdminMailInspectionSubmissionPayloadProtector protector =
                new AdminMailInspectionSubmissionPayloadProtector(properties);
        MailInspectionSubmissionMessageFactory messageFactory =
                new MailInspectionSubmissionMessageFactory(
                        new MailInspectionSubmissionChunker(properties),
                        protector,
                        clock);
        AtomicInteger publishCalls = new AtomicInteger();
        MailInspectionSubmissionPublisher publisher = message ->
                publishCalls.incrementAndGet() == 1
                        ? Mono.error(new IllegalStateException(
                                "isolated publisher failure"))
                        : Mono.empty();
        AdminMailInspectionJobServiceImpl service =
                new AdminMailInspectionJobServiceImpl(
                        new MailboxCredentialParser(properties),
                        new MailInspectionStrategyRegistry(
                                Map.of("openai", typedStrategy)),
                        store,
                        new MailInspectionRequestFingerprinterImpl(properties),
                        messageFactory,
                        publisher,
                        new RecordingSubmissionControl(),
                        new NoopWorkListenerControl(),
                        ids,
                        new PublicIdCodec(),
                        properties,
                        clock,
                        new MailInspectionTypeLifecycleGuard());
        AdminMailInspectionCreateCommand command =
                new AdminMailInspectionCreateCommand(
                        "550e8400-e29b-41d4-a716-446655440000",
                        List.of(
                                "owner@example.test----unused----"
                                        + "11111111-1111-1111-1111-111111111111----refresh"),
                        4);

        StepVerifier.create(service.create(
                        MailInspectionType.OPENAI_STATUS, command))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(AdminException.class);
                    assertThat(((AdminException) error).code())
                            .isEqualTo(
                                    AdminErrorCode
                                            .ADMIN_MAIL_INSPECTION_SUBMISSION_INCOMPLETE);
                })
                .verify();

        assertThat(service.get(9L).status())
                .isEqualTo(
                        MailInspectionJobStatus
                                .AWAITING_CLIENT_RESUBMISSION);

        StepVerifier.create(service.create(
                        MailInspectionType.OPENAI_STATUS, command))
                .assertNext(replayed -> {
                    assertThat(replayed.jobId())
                            .isEqualTo(new PublicIdCodec().encode(9L));
                    assertThat(replayed.idempotencyReplayed()).isTrue();
                    assertThat(replayed.confirmedSubmissionChunkCount())
                            .isEqualTo(1);
                })
                .verifyComplete();
        assertThat(publishCalls).hasValue(2);
    }

    private static final class RecordingSubmissionControl
            implements MailInspectionSubmissionListenerControl {

        private int started;

        @Override
        public Mono<Void> start(MailInspectionType type) {
            started++;
            return Mono.empty();
        }

        @Override
        public void stop(MailInspectionType type) {
        }

        @Override
        public void stopAll() {
        }
    }

    private static final class NoopWorkListenerControl
            implements MailInspectionListenerControl {

        @Override
        public Mono<Void> prepare(
                MailInspectionType type,
                int businessConcurrency) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> start(
                MailInspectionType type,
                int businessConcurrency) {
            return Mono.empty();
        }

        @Override
        public void stop(MailInspectionType type) {
        }

        @Override
        public void stopAll() {
        }
    }
}
