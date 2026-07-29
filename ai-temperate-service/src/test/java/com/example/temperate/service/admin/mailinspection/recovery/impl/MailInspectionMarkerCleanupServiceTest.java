package com.example.temperate.service.admin.mailinspection.recovery.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionJobKeyHasher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证运行期 Marker 清理只 ACK 完整终态账本，并使用独立物理恢复会话。
 */
final class MailInspectionMarkerCleanupServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T06:00:00Z");

    @Test
    void clearsCompleteMarkerLedgerWhenTypeHasNoActiveJob()
            throws Exception {
        HybridBase64UrlCodec publicIdCodec = new HybridBase64UrlCodec();
        String jobId = publicIdCodec.encode(new byte[16]);
        MailInspectionJobKeyHasher keyHasher =
                new MailInspectionJobKeyHasher(
                        AdminMailInspectionProperties.defaults(),
                        publicIdCodec);
        String jobHash = keyHasher.hashJobId(jobId).value();
        List<MailInspectionDispatchMarkerMessage> markers = List.of(
                marker(jobId, jobHash, 0),
                marker(jobId, jobHash, 1));
        MailInspectionRecoveryConnectionFactory connectionFactory =
                mock(MailInspectionRecoveryConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        AMQP.Queue.DeclareOk emptyQueue =
                mock(AMQP.Queue.DeclareOk.class);
        AtomicInteger markerReads = new AtomicInteger();
        when(emptyQueue.getMessageCount()).thenReturn(0);
        when(jobStore.findActiveJobs()).thenReturn(List.of());
        when(connectionFactory.open(
                any(MailInspectionType.class),
                eq("marker-cleanup"))).thenAnswer(ignored ->
                        new MailInspectionRecoverySession(
                                connection,
                                channel));
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);
        when(channel.queueDeclarePassive(anyString()))
                .thenReturn(emptyQueue);
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
            return response(index + 1L, queue, Integer.toString(index));
        });
        when(objectMapper.readValue(
                any(byte[].class),
                eq(MailInspectionDispatchMarkerMessage.class)))
                .thenAnswer(invocation -> markers.get(Integer.parseInt(
                        new String(invocation.getArgument(0), UTF_8))));

        MailInspectionMarkerCleanupServiceImpl service =
                new MailInspectionMarkerCleanupServiceImpl(
                        connectionFactory,
                        new MailInspectionRecoveryPlanner(),
                        new MailInspectionTypeLifecycleGuard(),
                        mock(MailInspectionRecoveryObserver.class),
                        objectMapper,
                        jobStore,
                        publicIdCodec,
                        keyHasher,
                        AdminMailInspectionProperties.defaults());

        service.cleanupTerminalMarkers();

        verify(jobStore).findActiveJobs();
        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
    }

    private static GetResponse response(
            long deliveryTag,
            String queue,
            String body) {
        return new GetResponse(
                new Envelope(deliveryTag, false, "", queue),
                new AMQP.BasicProperties(),
                body.getBytes(UTF_8),
                0);
    }

    private static MailInspectionDispatchMarkerMessage marker(
            String jobId,
            String jobHash,
            int chunkIndex) {
        return new MailInspectionDispatchMarkerMessage(
                "marker-" + chunkIndex,
                MailInspectionRabbitNames.DISPATCH_MARKER_EVENT_TYPE,
                MailInspectionRabbitNames.DISPATCH_MARKER_SCHEMA_VERSION,
                NOW,
                "trace-test",
                "550e8400-e29b-41d4-a716-446655440000",
                "A".repeat(43),
                jobId,
                jobHash,
                MailInspectionType.OPENAI_STATUS,
                chunkIndex,
                2,
                4,
                NOW,
                NOW);
    }
}
