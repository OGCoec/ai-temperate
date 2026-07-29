package com.example.temperate.web.admin.mailinspection.sse.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventSubscriber;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventType;
import com.example.temperate.web.admin.mailinspection.config.AdminMailInspectionSseProperties;
import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseEmitterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 验证 SSE 注册表在快照期间按 revision 暂存通知，并强制执行单管理员会话连接上限。
 */
final class MailInspectionSseEmitterRegistryImplTest {

    @Test
    void buffersSortsAndDropsNotificationsCoveredBySnapshot() {
        CapturingSubscriber subscriber = new CapturingSubscriber();
        MailInspectionSseEmitterRegistryImpl registry =
                new MailInspectionSseEmitterRegistryImpl(
                        properties(4),
                        subscriber);
        MailInspectionSseEmitterRegistry.Registration registration =
                registry.register(
                        "session",
                        "AZ9nEjRWeJCrze8SNFZ4kA",
                        "H".repeat(43),
                        (ignored, event) -> { });

        subscriber.publish(event(9));
        subscriber.publish(event(6));
        subscriber.publish(event(8));

        List<MailInspectionJobEvent> buffered =
                registration.activate(7L);

        assertThat(buffered)
                .extracting(MailInspectionJobEvent::revision)
                .containsExactly(8L, 9L);
        registration.close();
    }

    @Test
    void rejectsTheConnectionBeyondThePerAdminLimit() {
        MailInspectionSseEmitterRegistryImpl registry =
                new MailInspectionSseEmitterRegistryImpl(
                        properties(1),
                        new CapturingSubscriber());
        MailInspectionSseEmitterRegistry.Registration first =
                registry.register(
                        "same-session",
                        "AZ9nEjRWeJCrze8SNFZ4kA",
                        "H".repeat(43),
                        (ignored, event) -> { });

        assertThatThrownBy(() -> registry.register(
                        "same-session",
                        "BZ9nEjRWeJCrze8SNFZ4kA",
                        "I".repeat(43),
                        (ignored, event) -> { }))
                .isInstanceOf(AdminException.class);
        first.close();

        MailInspectionSseEmitterRegistry.Registration replacement =
                registry.register(
                        "same-session",
                        "BZ9nEjRWeJCrze8SNFZ4kA",
                        "I".repeat(43),
                        (ignored, event) -> { });
        assertThat(registry.registrations()).containsExactly(replacement);
        replacement.close();
    }

    @Test
    void tracksOutOfOrderResultLinesWithoutTreatingTheMaximumAsACursor() {
        MailInspectionSseEmitterRegistryImpl registry =
                new MailInspectionSseEmitterRegistryImpl(
                        properties(4),
                        new CapturingSubscriber());
        MailInspectionSseEmitterRegistry.Registration registration =
                registry.register(
                        "session",
                        "AZ9nEjRWeJCrze8SNFZ4kA",
                        "H".repeat(43),
                        (ignored, event) -> { });

        assertThat(registration.markResultSent(10)).isTrue();
        assertThat(registration.markResultSent(5)).isTrue();
        assertThat(registration.markResultSent(10)).isFalse();
        registration.resetResultCursor();
        assertThat(registration.markResultSent(5)).isTrue();
        registration.close();
    }

    private static AdminMailInspectionSseProperties properties(
            int maximum) {
        return new AdminMailInspectionSseProperties(
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                Duration.ofMinutes(30),
                maximum);
    }

    private static MailInspectionJobEvent event(long revision) {
        return new MailInspectionJobEvent(
                MailInspectionJobEvent.SCHEMA_VERSION,
                "H".repeat(43),
                revision,
                MailInspectionJobEventType.PROGRESS,
                MailInspectionType.OPENAI_STATUS,
                Instant.parse("2026-07-29T10:00:00Z"));
    }

    private static final class CapturingSubscriber
            implements MailInspectionJobEventSubscriber {

        private final AtomicReference<Consumer<MailInspectionJobEvent>>
                listener = new AtomicReference<>();

        @Override
        public AutoCloseable subscribe(
                Consumer<MailInspectionJobEvent> value) {
            listener.set(value);
            return () -> listener.compareAndSet(value, null);
        }

        private void publish(MailInspectionJobEvent event) {
            listener.get().accept(event);
        }
    }
}
