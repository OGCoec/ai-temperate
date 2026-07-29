package com.example.temperate.service.admin.mailinspection.recovery.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionProtectedCredential;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionProtectedPayload;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunker;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkMessage;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryConnectionFactory;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryObserver;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoverySession;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证恢复器按任务分组、逐条结算消息，并把失败隔离到对应检查类型。
 */
final class MailInspectionRecoveryCoordinatorImplTest {

    private static final Instant NOW =
            Instant.parse("2026-07-28T12:00:00Z");

    @Test
    void recoversAndRequeuesMoreThanOneHundredMessages() throws Exception {
        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionPayloadProtector protector =
                mock(AdminMailInspectionPayloadProtector.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        PublicIdCodec publicIdCodec = new PublicIdCodec();
        List<MailInspectionWorkMessage> messages = messages(publicIdCodec, 101);
        AtomicInteger openAiIndex = new AtomicInteger();

        when(connectionFactory.open(
                any(MailInspectionType.class),
                anyString())).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.basicGet(anyString(), eq(false))).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (!MailInspectionRabbitNames.OPENAI_QUEUE.equals(queue)) {
                return null;
            }
            int index = openAiIndex.getAndIncrement();
            if (index >= messages.size()) {
                return null;
            }
            int deliveryTag = index + 1;
            return new GetResponse(
                    new Envelope(deliveryTag, false, "", queue),
                    new AMQP.BasicProperties(),
                    Integer.toString(index).getBytes(UTF_8),
                    messages.size() - deliveryTag);
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionWorkMessage.class)))
                .thenAnswer(invocation -> messages.get(Integer.parseInt(
                        new String(invocation.getArgument(0), UTF_8))));
        when(protector.unprotect(
                anyString(),
                anyString(),
                any(MailInspectionType.class),
                anyInt(),
                any(MailInspectionProtectedPayload.class)))
                .thenAnswer(invocation -> new MailInspectionProtectedCredential(
                        "user" + invocation.getArgument(3) + "@example.test",
                        "00000000-0000-0000-0000-000000000000",
                        "refresh-token"));

        MailInspectionRecoveryCoordinatorImpl coordinator =
                new MailInspectionRecoveryCoordinatorImpl(
                        connectionFactory,
                        new MailInspectionRecoveryPlanner(),
                        new MailInspectionTypeLifecycleGuard(),
                        mock(MailInspectionRecoveryObserver.class),
                        objectMapper,
                        protector,
                        mock(AdminMailInspectionSubmissionPayloadProtector.class),
                        jobStore,
                        publicIdCodec,
                        AdminMailInspectionProperties.defaults(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.recoverAll().block();

        ArgumentCaptor<MailInspectionJobState> stateCaptor =
                ArgumentCaptor.forClass(MailInspectionJobState.class);
        verify(jobStore).restore(stateCaptor.capture());
        verify(channel).basicNack(101L, false, true);
        verify(jobStore).startAccepting(
                MailInspectionType.OPENAI_STATUS);
        var snapshot = stateCaptor.getValue().snapshot();
        assertThat(snapshot.status())
                .isEqualTo(MailInspectionJobStatus.AWAITING_ADMIN_RESUME);
        assertThat(snapshot.remainingCount()).isEqualTo(101);
        assertThat(snapshot.pendingItems()).hasSize(101);
        assertThat(snapshot.pendingItems().getLast().lineNumber())
                .isEqualTo(101);
    }

    @Test
    void recoversSubmissionAndPersistedWorkWithoutMarkerAsPausedTask()
            throws Exception {
        AdminMailInspectionProperties properties =
                AdminMailInspectionProperties.defaults();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PublicIdCodec publicIdCodec = new PublicIdCodec();
        long internalId = 11L;
        String publicId = publicIdCodec.encode(internalId);
        String clientRequestId =
                "550e8400-e29b-41d4-a716-446655440000";
        MailInspectionRequestFingerprint fingerprint =
                new MailInspectionRequestFingerprint("B".repeat(43));
        AdminMailInspectionSubmissionPayloadProtector submissionProtector =
                new AdminMailInspectionSubmissionPayloadProtector(properties);
        MailInspectionSubmissionMessageFactory submissionFactory =
                new MailInspectionSubmissionMessageFactory(
                        new MailInspectionSubmissionChunker(properties),
                        submissionProtector,
                        clock);
        MailInspectionSubmissionChunkMessage submission =
                submissionFactory.createChunks(
                                clientRequestId,
                                fingerprint,
                                internalId,
                                publicId,
                                MailInspectionType.OPENAI_STATUS,
                                1,
                                1,
                                0,
                                0,
                                4,
                                NOW,
                                List.of(new MailboxCredential(
                                        1,
                                        "user@example.test",
                                        "11111111-1111-1111-1111-111111111111",
                                        "refresh-token")))
                        .getFirst();
        MailInspectionWorkMessage work =
                new MailInspectionRabbitMessageFactory(
                                new AdminMailInspectionPayloadProtector(
                                        properties),
                                clock)
                        .createFromSubmission(
                                submission,
                                submissionProtector.unprotect(submission)
                                        .getFirst());

        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        AtomicInteger submissionReads = new AtomicInteger();
        AtomicInteger workReads = new AtomicInteger();

        when(connectionFactory.open(
                any(MailInspectionType.class),
                anyString())).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.basicGet(anyString(), eq(false))).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (MailInspectionRabbitNames.OPENAI_SUBMISSION_QUEUE.equals(queue)
                    && submissionReads.getAndIncrement() == 0) {
                return response(1L, queue, "submission", 0);
            }
            if (MailInspectionRabbitNames.OPENAI_QUEUE.equals(queue)
                    && workReads.getAndIncrement() == 0) {
                return response(2L, queue, "work", 0);
            }
            return null;
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionSubmissionChunkMessage.class)))
                .thenReturn(submission);
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionWorkMessage.class)))
                .thenReturn(work);

        MailInspectionRecoveryCoordinatorImpl coordinator =
                new MailInspectionRecoveryCoordinatorImpl(
                        connectionFactory,
                        new MailInspectionRecoveryPlanner(),
                        new MailInspectionTypeLifecycleGuard(),
                        mock(MailInspectionRecoveryObserver.class),
                        objectMapper,
                        new AdminMailInspectionPayloadProtector(properties),
                        submissionProtector,
                        jobStore,
                        publicIdCodec,
                        properties,
                        clock);

        coordinator.recoverAll().block();

        ArgumentCaptor<MailInspectionJobState> stateCaptor =
                ArgumentCaptor.forClass(MailInspectionJobState.class);
        verify(jobStore).restore(stateCaptor.capture());
        verify(channel).basicNack(2L, false, true);
        MailInspectionJobState recovered = stateCaptor.getValue();
        assertThat(recovered.status())
                .isEqualTo(MailInspectionJobStatus.AWAITING_ADMIN_RESUME);
        assertThat(recovered.recoveredAfterRestart()).isTrue();
        assertThat(recovered.confirmedSubmissionChunkCount()).isEqualTo(1);
        assertThat(recovered.dispatchedSubmissionChunkCount()).isZero();
        assertThat(recovered.snapshot().remainingCount()).isEqualTo(1);
        assertThat(recovered.snapshot().pendingItems())
                .extracting(item -> item.lineNumber())
                .containsExactly(1);
    }

    @Test
    void clearsTwoHistoricalTerminalMarkerGroupsWithoutRestoringAJob()
            throws Exception {
        PublicIdCodec publicIdCodec = new PublicIdCodec();
        List<MailInspectionDispatchMarkerMessage> markers = List.of(
                marker(publicIdCodec, 21L, "A".repeat(43)),
                marker(publicIdCodec, 22L, "B".repeat(43)));
        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        AtomicInteger markerReads = new AtomicInteger();
        when(connectionFactory.open(
                any(MailInspectionType.class),
                anyString())).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.basicGet(anyString(), eq(false))).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (!MailInspectionRabbitNames.OPENAI_DISPATCH_STATE_QUEUE
                    .equals(queue)) {
                return null;
            }
            int index = markerReads.getAndIncrement();
            if (index >= markers.size()) {
                return null;
            }
            return response(index + 1L, queue, Integer.toString(index), 0);
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionDispatchMarkerMessage.class)))
                .thenAnswer(invocation -> markers.get(Integer.parseInt(
                        new String(invocation.getArgument(0), UTF_8))));

        MailInspectionRecoveryCoordinatorImpl coordinator =
                new MailInspectionRecoveryCoordinatorImpl(
                        connectionFactory,
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

        coordinator.recoverAll().block();

        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
        verify(jobStore, never()).restore(any());
        verify(jobStore).startAccepting(
                MailInspectionType.OPENAI_STATUS);
    }

    @Test
    void clearsHistoricalMarkerAndRestoresOneActiveJobInSameType()
            throws Exception {
        PublicIdCodec publicIdCodec = new PublicIdCodec();
        MailInspectionDispatchMarkerMessage historical =
                marker(publicIdCodec, 31L, "C".repeat(43));
        MailInspectionWorkMessage active =
                messagesForJob(publicIdCodec, 32L, 1).getFirst();
        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionPayloadProtector protector =
                mock(AdminMailInspectionPayloadProtector.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        AtomicInteger markerReads = new AtomicInteger();
        AtomicInteger workReads = new AtomicInteger();
        when(connectionFactory.open(
                any(MailInspectionType.class),
                anyString())).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.basicGet(anyString(), eq(false))).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (MailInspectionRabbitNames.OPENAI_DISPATCH_STATE_QUEUE
                            .equals(queue)
                    && markerReads.getAndIncrement() == 0) {
                return response(1L, queue, "marker", 0);
            }
            if (MailInspectionRabbitNames.OPENAI_QUEUE.equals(queue)
                    && workReads.getAndIncrement() == 0) {
                return response(2L, queue, "work", 0);
            }
            return null;
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionDispatchMarkerMessage.class)))
                .thenReturn(historical);
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionWorkMessage.class)))
                .thenReturn(active);
        when(protector.unprotect(
                anyString(),
                anyString(),
                any(MailInspectionType.class),
                anyInt(),
                any(MailInspectionProtectedPayload.class)))
                .thenReturn(new MailInspectionProtectedCredential(
                        "masked@example.test",
                        "00000000-0000-0000-0000-000000000000",
                        "refresh-token"));

        MailInspectionRecoveryCoordinatorImpl coordinator =
                new MailInspectionRecoveryCoordinatorImpl(
                        connectionFactory,
                        new MailInspectionRecoveryPlanner(),
                        new MailInspectionTypeLifecycleGuard(),
                        mock(MailInspectionRecoveryObserver.class),
                        objectMapper,
                        protector,
                        mock(AdminMailInspectionSubmissionPayloadProtector.class),
                        jobStore,
                        publicIdCodec,
                        AdminMailInspectionProperties.defaults(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.recoverAll().block();

        verify(channel).basicAck(1L, false);
        verify(channel).basicNack(2L, false, true);
        verify(jobStore).restore(any(MailInspectionJobState.class));
        verify(jobStore).startAccepting(
                MailInspectionType.OPENAI_STATUS);
    }

    @Test
    void malformedMessageIsExplicitlyRequeuedAndOnlyItsTypeIsUnavailable()
            throws Exception {
        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        AtomicInteger markerReads = new AtomicInteger();
        when(connectionFactory.open(
                any(MailInspectionType.class),
                anyString())).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.basicGet(anyString(), eq(false))).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (MailInspectionRabbitNames.OPENAI_DISPATCH_STATE_QUEUE
                            .equals(queue)
                    && markerReads.getAndIncrement() == 0) {
                return response(1L, queue, "invalid", 0);
            }
            return null;
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionDispatchMarkerMessage.class)))
                .thenThrow(new com.fasterxml.jackson.core.JsonParseException(
                        null,
                        "invalid"));

        MailInspectionRecoveryCoordinatorImpl coordinator =
                new MailInspectionRecoveryCoordinatorImpl(
                        connectionFactory,
                        new MailInspectionRecoveryPlanner(),
                        new MailInspectionTypeLifecycleGuard(),
                        mock(MailInspectionRecoveryObserver.class),
                        objectMapper,
                        mock(AdminMailInspectionPayloadProtector.class),
                        mock(AdminMailInspectionSubmissionPayloadProtector.class),
                        jobStore,
                        new PublicIdCodec(),
                        AdminMailInspectionProperties.defaults(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.recoverAll().block();

        verify(channel).basicNack(1L, false, true);
        verify(jobStore).markUnavailable(
                MailInspectionType.OPENAI_STATUS,
                "RECOVERY_MESSAGE_DESERIALIZE");
        verify(jobStore, times(1)).startAccepting(
                MailInspectionType.KIRO_STATUS);
    }

    @Test
    void nackFailureAbortsPhysicalConnectionSoRabbitCanRequeueUnacked()
            throws Exception {
        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        MailInspectionRecoveryObserver observer =
                mock(MailInspectionRecoveryObserver.class);
        AtomicInteger markerReads = new AtomicInteger();
        when(connectionFactory.open(
                any(MailInspectionType.class),
                anyString())).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.basicGet(anyString(), eq(false))).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (MailInspectionRabbitNames.OPENAI_DISPATCH_STATE_QUEUE
                            .equals(queue)
                    && markerReads.getAndIncrement() == 0) {
                return response(1L, queue, "invalid", 0);
            }
            return null;
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionDispatchMarkerMessage.class)))
                .thenThrow(new com.fasterxml.jackson.core.JsonParseException(
                        null,
                        "invalid"));
        doThrow(new IOException("nack failed"))
                .when(channel)
                .basicNack(1L, false, true);

        MailInspectionRecoveryCoordinatorImpl coordinator =
                new MailInspectionRecoveryCoordinatorImpl(
                        connectionFactory,
                        new MailInspectionRecoveryPlanner(),
                        new MailInspectionTypeLifecycleGuard(),
                        observer,
                        objectMapper,
                        mock(AdminMailInspectionPayloadProtector.class),
                        mock(AdminMailInspectionSubmissionPayloadProtector.class),
                        jobStore,
                        new PublicIdCodec(),
                        AdminMailInspectionProperties.defaults(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.recoverAll().block();

        verify(channel).basicNack(1L, false, true);
        verify(connection).abort();
        verify(observer).nackRequeueFailed(
                MailInspectionType.OPENAI_STATUS);
        verify(jobStore).markUnavailable(
                MailInspectionType.OPENAI_STATUS,
                "RECOVERY_MESSAGE_DESERIALIZE");
    }

    @Test
    void twoActiveJobsInOneTypeAreRequeuedAndFailClosed()
            throws Exception {
        PublicIdCodec publicIdCodec = new PublicIdCodec();
        List<MailInspectionWorkMessage> work = List.of(
                messagesForJob(publicIdCodec, 41L, 1).getFirst(),
                messagesForJob(publicIdCodec, 42L, 1).getFirst());
        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionPayloadProtector protector =
                mock(AdminMailInspectionPayloadProtector.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        AtomicInteger workReads = new AtomicInteger();

        when(connectionFactory.open(
                any(MailInspectionType.class),
                anyString())).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.basicGet(anyString(), eq(false))).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (!MailInspectionRabbitNames.OPENAI_QUEUE.equals(queue)) {
                return null;
            }
            int index = workReads.getAndIncrement();
            if (index >= work.size()) {
                return null;
            }
            return response(
                    index + 1L,
                    queue,
                    Integer.toString(index),
                    work.size() - index - 1);
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionWorkMessage.class)))
                .thenAnswer(invocation -> work.get(Integer.parseInt(
                        new String(invocation.getArgument(0), UTF_8))));
        when(protector.unprotect(
                anyString(),
                anyString(),
                any(MailInspectionType.class),
                anyInt(),
                any(MailInspectionProtectedPayload.class)))
                .thenReturn(new MailInspectionProtectedCredential(
                        "masked@example.test",
                        "00000000-0000-0000-0000-000000000000",
                        "refresh-token"));

        MailInspectionRecoveryCoordinatorImpl coordinator =
                new MailInspectionRecoveryCoordinatorImpl(
                        connectionFactory,
                        new MailInspectionRecoveryPlanner(),
                        new MailInspectionTypeLifecycleGuard(),
                        mock(MailInspectionRecoveryObserver.class),
                        objectMapper,
                        protector,
                        mock(AdminMailInspectionSubmissionPayloadProtector.class),
                        jobStore,
                        publicIdCodec,
                        AdminMailInspectionProperties.defaults(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        coordinator.recoverAll().block();

        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true);
        verify(jobStore, never()).restore(any());
        verify(jobStore).markUnavailable(
                MailInspectionType.OPENAI_STATUS,
                "RECOVERY_GROUP_PLAN");
    }

    private static GetResponse response(
            long deliveryTag,
            String queue,
            String body,
            int messageCount) {
        return new GetResponse(
                new Envelope(deliveryTag, false, "", queue),
                new AMQP.BasicProperties(),
                body.getBytes(UTF_8),
                messageCount);
    }

    private static List<MailInspectionWorkMessage> messages(
            PublicIdCodec publicIdCodec,
            int count) {
        return messagesForJob(publicIdCodec, 7L, count);
    }

    private static List<MailInspectionWorkMessage> messagesForJob(
            PublicIdCodec publicIdCodec,
            long internalId,
            int count) {
        List<MailInspectionWorkMessage> messages = new ArrayList<>(count);
        String publicId = publicIdCodec.encode(internalId);
        for (int index = 1; index <= count; index++) {
            messages.add(new MailInspectionWorkMessage(
                    "message-" + index,
                    MailInspectionRabbitNames.EVENT_TYPE,
                    MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION,
                    NOW,
                    "trace-test",
                    internalId,
                    publicId,
                    MailInspectionType.OPENAI_STATUS,
                    index,
                    count,
                    count,
                    0,
                    0,
                    4,
                    NOW,
                    new MailInspectionProtectedPayload("iv", "ciphertext")));
        }
        return List.copyOf(messages);
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
