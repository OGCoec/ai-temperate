package com.example.temperate.web.admin.mailinspection.sse.impl;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionJobResponse;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionResultResponse;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionSseEventResponse;
import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseEmitterRegistry.Registration;
import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseEmitterRegistry;
import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseService;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 从 Redis 发送一致快照、分批结果和实时事件，并通过心跳 revision 校准弥补 Pub/Sub 丢失。
 */
@Service
public final class MailInspectionSseServiceImpl
        implements MailInspectionSseService {

    private static final int CONSISTENT_SNAPSHOT_ATTEMPTS = 3;

    private final AdminMailInspectionJobStore jobStore;
    private final MailInspectionSseEmitterRegistry registry;
    private final AdminMailInspectionProperties jobProperties;

    public MailInspectionSseServiceImpl(
            AdminMailInspectionJobStore jobStore,
            MailInspectionSseEmitterRegistry registry,
            AdminMailInspectionProperties jobProperties) {
        this.jobStore = Objects.requireNonNull(jobStore);
        this.registry = Objects.requireNonNull(registry);
        this.jobProperties = Objects.requireNonNull(jobProperties);
    }

    @Override
    public SseEmitter connect(
            String jobId,
            String lastEventId,
            String adminSessionKey) {
        validateLastEventId(lastEventId);
        MailInspectionRedisJobDocument document = requiredMeta(jobId);
        Registration registration = registry.register(
                adminSessionKey,
                jobId,
                document.jobHash(),
                this::onEvent);
        try {
            MailInspectionJobSnapshot snapshot =
                    consistentSnapshot(jobId);
            sendSnapshot(registration, snapshot);
            // 激活与暂存事件回放必须持有同一连接锁，防止新实时通知越过较早的暂存 revision。
            synchronized (registration) {
                List<MailInspectionJobEvent> buffered =
                        registration.activate(snapshot.revision());
                buffered.forEach(event -> onEvent(registration, event));
            }
            if (snapshot.status().terminal() && !registration.closed()) {
                send(registration, "terminal", snapshot.revision(),
                        meta(snapshot), true);
                registration.close();
            }
            return registration.emitter();
        } catch (RuntimeException exception) {
            registration.closeWithError(exception);
            throw exception;
        }
    }

    @Scheduled(
            fixedDelayString =
                    "${app.admin.mail-inspection.sse.heartbeat-interval:15s}")
    public void heartbeat() {
        Map<String, List<Registration>> byJob = new LinkedHashMap<>();
        for (Registration registration : registry.registrations()) {
            if (!registration.closed()) {
                byJob.computeIfAbsent(
                                registration.jobId(),
                                ignored -> new java.util.ArrayList<>())
                        .add(registration);
            }
        }
        Map<String, MailInspectionRedisJobDocument> documents;
        try {
            documents = jobStore.findSnapshotMetas(byJob.keySet());
        } catch (RuntimeException exception) {
            byJob.values().stream()
                    .flatMap(List::stream)
                    .forEach(registration ->
                            registration.closeWithError(exception));
            return;
        }
        for (Map.Entry<String, List<Registration>> entry :
                byJob.entrySet()) {
            reconcileHeartbeatGroup(
                    entry.getKey(),
                    entry.getValue(),
                    documents.get(entry.getKey()));
        }
    }

    private void reconcileHeartbeatGroup(
            String jobId,
            List<Registration> registrations,
            MailInspectionRedisJobDocument document) {
        if (document == null) {
            registrations.forEach(registration -> {
                synchronized (registration) {
                    send(
                            registration,
                            "terminal",
                            registration.lastRevision(),
                            Map.of("status", "EXPIRED"),
                            true);
                    registration.close();
                }
            });
            return;
        }
        boolean requiresSnapshot = document.status().terminal()
                || registrations.stream().anyMatch(registration ->
                        document.revision() > registration.lastRevision());
        MailInspectionJobSnapshot snapshot = null;
        try {
            if (requiresSnapshot) {
                // 同一 Job 的全部连接共享一次权威快照，避免按管理员连接数重复读取结果桶。
                snapshot = consistentSnapshot(jobId);
            }
            for (Registration registration : registrations) {
                reconcileHeartbeatRegistration(
                        registration,
                        document,
                        snapshot);
            }
        } catch (RuntimeException exception) {
            registrations.forEach(registration ->
                    registration.closeWithError(exception));
        }
    }

    private void reconcileHeartbeatRegistration(
            Registration registration,
            MailInspectionRedisJobDocument document,
            MailInspectionJobSnapshot snapshot) {
        synchronized (registration) {
            if (registration.closed()) {
                return;
            }
            if (snapshot != null
                    && snapshot.revision() > registration.lastRevision()) {
                sendSnapshot(registration, snapshot);
            }
            if (snapshot != null && snapshot.status().terminal()) {
                send(
                        registration,
                        "terminal",
                        snapshot.revision(),
                        meta(snapshot),
                        true);
                registration.close();
                return;
            }
            long heartbeatRevision = Math.max(
                    document.revision(),
                    registration.lastRevision());
            send(
                    registration,
                    "heartbeat",
                    heartbeatRevision,
                    Map.of("status", document.status().name()),
                    true);
            registration.advance(heartbeatRevision);
        }
    }

    private void onEvent(
            Registration registration,
            MailInspectionJobEvent event) {
        synchronized (registration) {
            if (registration.closed()
                    || event.revision() <= registration.lastRevision()) {
                return;
            }
            try {
                MailInspectionJobSnapshot snapshot =
                        consistentSnapshot(registration.jobId());
                // 快照可能已经领先于当前 Pub/Sub 通知；先补齐结果，避免较早的进度通知推进 revision 后吞掉后续结果通知。
                sendNewResults(registration, snapshot);
                if (snapshot.status().terminal()) {
                    send(
                            registration,
                            "terminal",
                            snapshot.revision(),
                            meta(snapshot),
                            true);
                    registration.close();
                    return;
                }
                switch (event.eventType()) {
                    case RESULT -> {
                        // 结果事件已由上面的增量读取发送，不再重复发送状态载荷。
                    }
                    case PROGRESS -> send(
                            registration,
                            "progress",
                            snapshot.revision(),
                            meta(snapshot),
                            true);
                    case STATUS -> send(
                            registration,
                            "status",
                            snapshot.revision(),
                            meta(snapshot),
                            true);
                    case TERMINAL -> send(
                            registration,
                            "status",
                            snapshot.revision(),
                            meta(snapshot),
                            true);
                }
                registration.advance(snapshot.revision());
            } catch (RuntimeException exception) {
                registration.closeWithError(exception);
            }
        }
    }

    private void sendSnapshot(
            Registration registration,
            MailInspectionJobSnapshot snapshot) {
        // 快照元数据会让客户端清空旧结果，因此服务端也必须重置连接级去重集合并重新发送完整快照。
        registration.resetResultCursor();
        send(
                registration,
                "snapshot-meta",
                snapshot.revision(),
                meta(snapshot),
                false);
        int batchSize = jobProperties.job().snapshotBatchSize();
        List<MailInspectionResult> results = snapshot.results();
        for (int offset = 0; offset < results.size(); offset += batchSize) {
            List<MailInspectionResult> sourceBatch = results.subList(
                    offset,
                    Math.min(results.size(), offset + batchSize));
            List<AdminMailInspectionResultResponse> batch = sourceBatch
                    .stream()
                    .map(AdminMailInspectionResultResponse::from)
                    .toList();
            send(
                    registration,
                    "result-batch",
                    snapshot.revision(),
                    Map.of("results", batch),
                    false);
            sourceBatch.forEach(result ->
                    registration.markResultSent(result.lineNumber()));
        }
        send(
                registration,
                "sync-complete",
                snapshot.revision(),
                Map.of("resultCount", results.size()),
                true);
        registration.advance(snapshot.revision());
    }

    private void sendNewResults(
            Registration registration,
            MailInspectionJobSnapshot snapshot) {
        // 邮件行会乱序完成，最大行号不能作为增量游标；连接级集合只负责抑制重复发送，权威结果仍来自 Redis 快照。
        for (MailInspectionResult result : snapshot.results()) {
            if (registration.markResultSent(result.lineNumber())) {
                send(
                        registration,
                        "result",
                        snapshot.revision(),
                        AdminMailInspectionResultResponse.from(result),
                        true);
            }
            if (registration.closed()) {
                return;
            }
        }
        registration.advance(snapshot.revision());
    }

    private MailInspectionJobSnapshot consistentSnapshot(String jobId) {
        for (int attempt = 0;
                attempt < CONSISTENT_SNAPSHOT_ATTEMPTS;
                attempt++) {
            MailInspectionJobSnapshot snapshot = jobStore
                    .findSnapshot(jobId)
                    .orElseThrow(() -> notFound());
            MailInspectionRedisJobDocument after = requiredMeta(jobId);
            if (snapshot.revision() == after.revision()) {
                return snapshot;
            }
        }
        throw new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_UNAVAILABLE,
                "mail inspection snapshot changed too quickly");
    }

    private MailInspectionRedisJobDocument requiredMeta(String jobId) {
        return jobStore.findSnapshotMeta(jobId)
                .orElseThrow(MailInspectionSseServiceImpl::notFound);
    }

    private static Map<String, Object> meta(
            MailInspectionJobSnapshot snapshot) {
        AdminMailInspectionJobResponse response =
                AdminMailInspectionJobResponse.from(snapshot);
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("jobId", response.jobId());
        values.put("inspectionType", response.inspectionType());
        values.put("status", response.status());
        values.put("requestedCount", response.requestedCount());
        values.put("acceptedCount", response.acceptedCount());
        values.put("duplicateCount", response.duplicateCount());
        values.put("invalidCount", response.invalidCount());
        values.put("processedCount", response.processedCount());
        values.put("runningCount", response.runningCount());
        values.put("queuedCount", response.queuedCount());
        values.put("remainingCount", response.remainingCount());
        values.put("businessConcurrency", response.businessConcurrency());
        values.put("dispatchFailedCount", response.dispatchFailedCount());
        values.put("summary", response.summary());
        values.put("createdAt", response.createdAt());
        values.put("startedAt", response.startedAt());
        values.put("completedAt", response.completedAt());
        values.put("expiresAt", response.expiresAt());
        return Collections.unmodifiableMap(values);
    }

    private static void send(
            Registration registration,
            String name,
            long revision,
            Object data,
            boolean includeId) {
        if (registration.closed()) {
            return;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name(name)
                    .data(new AdminMailInspectionSseEventResponse(
                            revision, data));
            if (includeId) {
                builder.id(Long.toString(revision));
            }
            synchronized (registration) {
                registration.emitter().send(builder);
            }
        } catch (IOException | IllegalStateException exception) {
            registration.closeWithError(exception);
        }
    }

    private static void validateLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            if (Long.parseLong(value) < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_INVALID_REQUEST,
                    "Last-Event-ID is invalid");
        }
    }

    private static AdminException notFound() {
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                "mail inspection job not found");
    }
}
