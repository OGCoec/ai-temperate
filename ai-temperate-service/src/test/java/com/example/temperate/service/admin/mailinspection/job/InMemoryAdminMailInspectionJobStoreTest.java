package com.example.temperate.service.admin.mailinspection.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证进程内任务容量、幂等预留与按检查类型隔离的接收闸门。
 */
final class InMemoryAdminMailInspectionJobStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

    @Test
    void rejectsSecondActiveJobOfSameType() {
        InMemoryAdminMailInspectionJobStore store = new InMemoryAdminMailInspectionJobStore(
                AdminMailInspectionProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        store.startAccepting(MailInspectionType.OPENAI_STATUS);
        store.create(state(1L, MailInspectionType.OPENAI_STATUS));

        assertThatThrownBy(() -> store.create(
                        state(2L, MailInspectionType.OPENAI_STATUS)))
                .isInstanceOf(AdminException.class);
    }

    @Test
    void removesExpiredCompletedJob() {
        InMemoryAdminMailInspectionJobStore store = new InMemoryAdminMailInspectionJobStore(
                AdminMailInspectionProperties.defaults(),
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        store.startAccepting(MailInspectionType.OPENAI_STATUS);
        MailInspectionJobState state =
                state(1L, MailInspectionType.OPENAI_STATUS);
        state.markRunning(NOW);
        state.complete(NOW, java.time.Duration.ofMinutes(30));
        store.create(state);

        assertThat(store.find(1L)).isEmpty();
    }

    @Test
    void replaysSameIdempotencyKeyWithoutCreatingSecondJob() {
        InMemoryAdminMailInspectionJobStore store = store();
        MailInspectionJobState first = submitting(1L, "A".repeat(43));
        MailInspectionJobState duplicate = submitting(2L, "A".repeat(43));

        assertThat(store.reserveOrFind(
                        first.clientRequestId(),
                        first.requestFingerprint(),
                        first).status())
                .isEqualTo(MailInspectionJobReservationStatus.CREATED);
        var replay = store.reserveOrFind(
                duplicate.clientRequestId(),
                duplicate.requestFingerprint(),
                duplicate);

        assertThat(replay.status())
                .isEqualTo(MailInspectionJobReservationStatus.REPLAYED);
        assertThat(replay.state().internalId()).isEqualTo(1L);
    }

    @Test
    void rejectsFingerprintChangeForSameIdempotencyKey() {
        InMemoryAdminMailInspectionJobStore store = store();
        MailInspectionJobState first = submitting(1L, "A".repeat(43));
        MailInspectionJobState changed = submitting(2L, "B".repeat(43));
        store.reserveOrFind(
                first.clientRequestId(),
                first.requestFingerprint(),
                first);

        assertThat(store.reserveOrFind(
                        changed.clientRequestId(),
                        changed.requestFingerprint(),
                        changed).status())
                .isEqualTo(
                        MailInspectionJobReservationStatus.FINGERPRINT_CONFLICT);
    }

    @Test
    void isolatesAcceptanceStateByInspectionType() {
        InMemoryAdminMailInspectionJobStore store = store();
        store.markUnavailable(
                MailInspectionType.IP2LOCATION_VERIFY_LINK,
                "RECOVERY_MESSAGE_DESERIALIZE");

        store.create(state(1L, MailInspectionType.OPENAI_STATUS));

        assertThat(store.acceptanceState(MailInspectionType.OPENAI_STATUS))
                .isEqualTo(MailInspectionAcceptanceState.ACCEPTING);
        assertThat(store.acceptanceState(
                MailInspectionType.IP2LOCATION_VERIFY_LINK))
                .isEqualTo(MailInspectionAcceptanceState.UNAVAILABLE);
        assertThatThrownBy(() -> store.create(
                state(2L, MailInspectionType.IP2LOCATION_VERIFY_LINK)))
                .isInstanceOf(AdminException.class);
    }

    private static InMemoryAdminMailInspectionJobStore store() {
        InMemoryAdminMailInspectionJobStore store =
                new InMemoryAdminMailInspectionJobStore(
                AdminMailInspectionProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        for (MailInspectionType type : MailInspectionType.values()) {
            store.startAccepting(type);
        }
        return store;
    }

    private static MailInspectionJobState submitting(
            long id,
            String fingerprint) {
        return MailInspectionJobState.submitting(
                id,
                new com.example.temperate.common.codec.id.PublicIdCodec().encode(id),
                MailInspectionType.OPENAI_STATUS,
                1,
                1,
                0,
                0,
                4,
                "550e8400-e29b-41d4-a716-446655440000",
                new MailInspectionRequestFingerprint(fingerprint),
                1,
                NOW,
                java.time.Duration.ofHours(6),
                List.of());
    }

    private static MailInspectionJobState state(
            long id,
            MailInspectionType type) {
        return new MailInspectionJobState(
                id,
                new com.example.temperate.common.codec.id.PublicIdCodec().encode(id),
                type,
                1,
                1,
                0,
                0,
                NOW,
                List.of());
    }
}
