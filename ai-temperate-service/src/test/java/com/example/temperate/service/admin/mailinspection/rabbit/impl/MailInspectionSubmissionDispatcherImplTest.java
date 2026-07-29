package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.job.InMemoryAdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunker;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerPublisher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkPublisher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionMessageFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/**
 * 验证 Submission Dispatcher 只在全部 Work Confirm 和 Marker Confirm 完成后推进任务并启动工作消费者。
 */
final class MailInspectionSubmissionDispatcherImplTest {

    @Test
    void confirmsAllWorkThenMarkerBeforeStartingWorkListener() {
        Fixture fixture = fixture();
        List<String> events = new ArrayList<>();
        MailInspectionSubmissionDispatcherImpl dispatcher = dispatcher(
                fixture,
                message -> {
                    events.add("work:" + message.lineNumber());
                    return Mono.empty();
                },
                marker -> {
                    events.add("marker:" + marker.chunkIndex());
                    return Mono.empty();
                },
                events);

        StepVerifier.create(dispatcher.dispatch(
                        MailInspectionType.OPENAI_STATUS,
                        fixture.chunk()))
                .verifyComplete();

        assertThat(events)
                .containsSubsequence("marker:0", "prepare", "start");
        assertThat(events.indexOf("marker:0"))
                .isGreaterThan(events.indexOf("work:1"))
                .isGreaterThan(events.indexOf("work:2"));
        assertThat(fixture.state().status())
                .isEqualTo(MailInspectionJobStatus.RUNNING);
        assertThat(fixture.state().dispatchedSubmissionChunkCount())
                .isEqualTo(1);
    }

    @Test
    void waitsForAllWorkAndMarkerConfirmsBeforeStartingListener() {
        Fixture fixture = fixture();
        List<String> events = new ArrayList<>();
        Sinks.Empty<Void> firstWorkConfirm = Sinks.empty();
        Sinks.Empty<Void> secondWorkConfirm = Sinks.empty();
        Sinks.Empty<Void> markerConfirm = Sinks.empty();
        AtomicInteger markerCalls = new AtomicInteger();
        MailInspectionSubmissionDispatcherImpl dispatcher = dispatcher(
                fixture,
                message -> message.lineNumber() == 1
                        ? firstWorkConfirm.asMono()
                        : secondWorkConfirm.asMono(),
                marker -> {
                    markerCalls.incrementAndGet();
                    return markerConfirm.asMono();
                },
                events);

        StepVerifier.create(dispatcher.dispatch(
                        MailInspectionType.OPENAI_STATUS,
                        fixture.chunk()))
                .then(() -> {
                    assertThat(markerCalls).hasValue(0);
                    assertThat(events).isEmpty();
                })
                .then(firstWorkConfirm::tryEmitEmpty)
                .then(() -> {
                    assertThat(markerCalls).hasValue(0);
                    assertThat(events).isEmpty();
                })
                .then(secondWorkConfirm::tryEmitEmpty)
                .then(() -> {
                    assertThat(markerCalls).hasValue(1);
                    assertThat(events).isEmpty();
                    assertThat(fixture.state()
                            .dispatchedSubmissionChunkCount()).isZero();
                })
                .then(markerConfirm::tryEmitEmpty)
                .verifyComplete();

        assertThat(events).containsExactly("prepare", "start");
        assertThat(fixture.state().status())
                .isEqualTo(MailInspectionJobStatus.RUNNING);
    }

    @Test
    void markerFailureDoesNotCompleteChunkOrStartListener() {
        Fixture fixture = fixture();
        List<String> events = new ArrayList<>();
        MailInspectionSubmissionDispatcherImpl dispatcher = dispatcher(
                fixture,
                message -> Mono.empty(),
                marker -> Mono.error(new IllegalStateException(
                        "marker confirm failed")),
                events);

        StepVerifier.create(dispatcher.dispatch(
                        MailInspectionType.OPENAI_STATUS,
                        fixture.chunk()))
                .expectErrorMatches(exception ->
                        exception instanceof IllegalStateException
                                && "marker confirm failed".equals(
                                        exception.getMessage()))
                .verify();

        assertThat(events).isEmpty();
        assertThat(fixture.state().status())
                .isEqualTo(MailInspectionJobStatus.DISPATCHING);
        assertThat(fixture.state().dispatchedSubmissionChunkCount())
                .isZero();
    }

    private static MailInspectionSubmissionDispatcherImpl dispatcher(
            Fixture fixture,
            MailInspectionWorkPublisher workPublisher,
            MailInspectionDispatchMarkerPublisher markerPublisher,
            List<String> events) {
        return new MailInspectionSubmissionDispatcherImpl(
                fixture.store(),
                fixture.submissionProtector(),
                new MailInspectionRabbitMessageFactory(
                        new AdminMailInspectionPayloadProtector(
                                fixture.properties()),
                        fixture.clock()),
                fixture.submissionFactory(),
                workPublisher,
                markerPublisher,
                new RecordingListenerControl(events),
                fixture.codec(),
                fixture.properties(),
                fixture.clock());
    }

    private static Fixture fixture() {
        AdminMailInspectionProperties properties =
                AdminMailInspectionProperties.defaults();
        Instant now = Instant.parse("2026-07-28T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        PublicIdCodec codec = new PublicIdCodec();
        String publicId = codec.encode(9L);
        String clientRequestId =
                "550e8400-e29b-41d4-a716-446655440000";
        MailInspectionRequestFingerprint fingerprint =
                new MailInspectionRequestFingerprint("A".repeat(43));
        AdminMailInspectionSubmissionPayloadProtector submissionProtector =
                new AdminMailInspectionSubmissionPayloadProtector(properties);
        MailInspectionSubmissionMessageFactory submissionFactory =
                new MailInspectionSubmissionMessageFactory(
                        new MailInspectionSubmissionChunker(properties),
                        submissionProtector,
                        clock);
        List<MailboxCredential> credentials = List.of(
                credential(1, "one@example.test"),
                credential(2, "two@example.test"));
        MailInspectionSubmissionChunkMessage chunk =
                submissionFactory.createChunks(
                                clientRequestId,
                                fingerprint,
                                9L,
                                publicId,
                                MailInspectionType.OPENAI_STATUS,
                                2,
                                2,
                                0,
                                0,
                                4,
                                now,
                                credentials)
                        .getFirst();
        MailInspectionJobState state = MailInspectionJobState.submitting(
                9L,
                publicId,
                MailInspectionType.OPENAI_STATUS,
                2,
                2,
                0,
                0,
                4,
                clientRequestId,
                fingerprint,
                1,
                now,
                properties.submission().incompleteRetention(),
                List.of());
        state.confirmSubmissionChunk(
                0,
                now,
                properties.submission().incompleteRetention());
        InMemoryAdminMailInspectionJobStore store =
                new InMemoryAdminMailInspectionJobStore(properties, clock);
        store.startAccepting(MailInspectionType.OPENAI_STATUS);
        store.create(state);
        return new Fixture(
                properties,
                clock,
                codec,
                submissionProtector,
                submissionFactory,
                chunk,
                state,
                store);
    }

    private static MailboxCredential credential(
            int lineNumber,
            String email) {
        return new MailboxCredential(
                lineNumber,
                email,
                "11111111-1111-1111-1111-111111111111",
                "refresh-" + lineNumber);
    }

    private static final class RecordingListenerControl
            implements MailInspectionListenerControl {

        private final List<String> events;

        private RecordingListenerControl(List<String> events) {
            this.events = events;
        }

        @Override
        public Mono<Void> prepare(
                MailInspectionType type,
                int businessConcurrency) {
            events.add("prepare");
            return Mono.empty();
        }

        @Override
        public Mono<Void> start(
                MailInspectionType type,
                int businessConcurrency) {
            events.add("start");
            return Mono.empty();
        }

        @Override
        public void stop(MailInspectionType type) {
        }

        @Override
        public void stopAll() {
        }
    }

    private record Fixture(
            AdminMailInspectionProperties properties,
            Clock clock,
            PublicIdCodec codec,
            AdminMailInspectionSubmissionPayloadProtector submissionProtector,
            MailInspectionSubmissionMessageFactory submissionFactory,
            MailInspectionSubmissionChunkMessage chunk,
            MailInspectionJobState state,
            InMemoryAdminMailInspectionJobStore store) {
    }
}
