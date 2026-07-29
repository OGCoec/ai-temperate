package com.example.temperate.web.admin.mailinspection.sse.impl;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventSubscriber;
import com.example.temperate.web.admin.mailinspection.config.AdminMailInspectionSseProperties;
import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseEmitterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 在 JVM 中只保存短期 SSE 连接句柄和游标，不保存任何邮件任务权威状态。
 */
@Component
public final class MailInspectionSseEmitterRegistryImpl
        implements MailInspectionSseEmitterRegistry {

    private final AdminMailInspectionSseProperties properties;
    private final ConcurrentHashMap<String, Set<RegistrationImpl>>
            bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<RegistrationImpl>>
            byJobHash = new ConcurrentHashMap<>();
    private final AutoCloseable subscription;

    public MailInspectionSseEmitterRegistryImpl(
            AdminMailInspectionSseProperties properties,
            MailInspectionJobEventSubscriber subscriber) {
        this.properties = Objects.requireNonNull(properties);
        this.subscription = Objects.requireNonNull(subscriber)
                .subscribe(this::dispatch);
    }

    @Override
    public Registration register(
            String adminSessionKey,
            String jobId,
            String jobHash,
            BiConsumer<Registration, MailInspectionJobEvent> listener) {
        Objects.requireNonNull(adminSessionKey);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(jobHash);
        Objects.requireNonNull(listener);
        AtomicReference<RegistrationImpl> created = new AtomicReference<>();
        // compute 将上限检查和加入会话索引绑定到同一 Key 的原子更新，避免最后一条旧连接移除时把新连接留在脱离索引的 Set 中。
        bySession.compute(adminSessionKey, (ignored, existing) -> {
            Set<RegistrationImpl> sessionConnections = existing == null
                    ? ConcurrentHashMap.newKeySet()
                    : existing;
            if (sessionConnections.size()
                    >= properties.maxConnectionsPerAdmin()) {
                throw new AdminException(
                        AdminErrorCode
                                .ADMIN_MAIL_INSPECTION_SSE_CONNECTION_LIMIT,
                        "mail inspection SSE connection limit reached");
            }
            RegistrationImpl registration = new RegistrationImpl(
                    adminSessionKey,
                    jobId,
                    jobHash,
                    new SseEmitter(
                            properties.connectionTimeout().toMillis()),
                    listener);
            sessionConnections.add(registration);
            created.set(registration);
            return sessionConnections;
        });
        RegistrationImpl registration = created.get();
        byJobHash.compute(jobHash, (ignored, existing) -> {
            Set<RegistrationImpl> jobConnections = existing == null
                    ? ConcurrentHashMap.newKeySet()
                    : existing;
            jobConnections.add(registration);
            return jobConnections;
        });
        registration.emitter().onCompletion(
                () -> remove(registration));
        registration.emitter().onTimeout(
                () -> remove(registration));
        registration.emitter().onError(
                ignored -> remove(registration));
        return registration;
    }

    @Override
    public List<Registration> registrations() {
        return byJobHash.values().stream()
                .flatMap(Set::stream)
                .map(value -> (Registration) value)
                .toList();
    }

    @PreDestroy
    void shutdown() {
        registrations().forEach(Registration::close);
        try {
            subscription.close();
        } catch (Exception ignored) {
            // 应用已进入关闭阶段，取消订阅失败不会改变 Redis 或 Rabbit 状态。
        }
    }

    private void dispatch(MailInspectionJobEvent event) {
        Set<RegistrationImpl> registrations =
                byJobHash.get(event.jobHash());
        if (registrations == null) {
            return;
        }
        registrations.forEach(registration ->
                registration.accept(event));
    }

    private void remove(RegistrationImpl registration) {
        if (!registration.markClosed()) {
            return;
        }
        removeFrom(bySession, registration.adminSessionKey(), registration);
        removeFrom(byJobHash, registration.jobHash(), registration);
    }

    private static void removeFrom(
            ConcurrentHashMap<String, Set<RegistrationImpl>> index,
            String key,
            RegistrationImpl registration) {
        index.computeIfPresent(key, (ignored, values) -> {
            values.remove(registration);
            return values.isEmpty() ? null : values;
        });
    }

    /**
     * 快照完成前只排队通知；激活时按 revision 排序并丢弃不大于快照版本的事件。
     */
    private final class RegistrationImpl implements Registration {

        private final String adminSessionKey;
        private final String jobId;
        private final String jobHash;
        private final SseEmitter emitter;
        private final BiConsumer<Registration, MailInspectionJobEvent> listener;
        private final ConcurrentLinkedQueue<MailInspectionJobEvent> buffered =
                new ConcurrentLinkedQueue<>();
        private final AtomicBoolean active = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong lastRevision = new AtomicLong();
        private final Set<Integer> sentResultLines =
                ConcurrentHashMap.newKeySet();

        private RegistrationImpl(
                String adminSessionKey,
                String jobId,
                String jobHash,
                SseEmitter emitter,
                BiConsumer<Registration, MailInspectionJobEvent> listener) {
            this.adminSessionKey = adminSessionKey;
            this.jobId = jobId;
            this.jobHash = jobHash;
            this.emitter = emitter;
            this.listener = listener;
        }

        private synchronized void accept(MailInspectionJobEvent event) {
            if (closed()) {
                return;
            }
            if (!active.get()) {
                buffered.add(event);
                return;
            }
            listener.accept(this, event);
        }

        @Override
        public SseEmitter emitter() {
            return emitter;
        }

        @Override
        public String jobId() {
            return jobId;
        }

        @Override
        public String jobHash() {
            return jobHash;
        }

        private String adminSessionKey() {
            return adminSessionKey;
        }

        @Override
        public long lastRevision() {
            return lastRevision.get();
        }

        @Override
        public boolean markResultSent(int lineNumber) {
            return sentResultLines.add(lineNumber);
        }

        @Override
        public void resetResultCursor() {
            sentResultLines.clear();
        }

        @Override
        public void advance(long revision) {
            lastRevision.accumulateAndGet(revision, Math::max);
        }

        @Override
        public synchronized List<MailInspectionJobEvent> activate(
                long snapshotRevision) {
            advance(snapshotRevision);
            active.set(true);
            List<MailInspectionJobEvent> events = new ArrayList<>();
            MailInspectionJobEvent event;
            while ((event = buffered.poll()) != null) {
                if (event.revision() > snapshotRevision) {
                    events.add(event);
                }
            }
            events.sort(Comparator.comparingLong(
                    MailInspectionJobEvent::revision));
            return List.copyOf(events);
        }

        @Override
        public boolean closed() {
            return closed.get();
        }

        @Override
        public void close() {
            remove(this);
            emitter.complete();
        }

        @Override
        public void closeWithError(Throwable failure) {
            remove(this);
            emitter.completeWithError(failure);
        }

        private boolean markClosed() {
            return closed.compareAndSet(false, true);
        }
    }
}
