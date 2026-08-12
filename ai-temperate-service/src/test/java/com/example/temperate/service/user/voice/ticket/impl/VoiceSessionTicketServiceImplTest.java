package com.example.temperate.service.user.voice.ticket.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketIssue;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketSnapshot;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketStore;
import com.example.temperate.service.user.voice.ticket.VoiceTicketSecurityBinding;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证语音票据签发、用途隔离保护和单次消费语义。
 */
final class VoiceSessionTicketServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void issuesCanonicalThirtySecondTicketAndConsumesItOnlyOnce() {
        InMemoryStore store = new InMemoryStore();
        VoiceSessionTicketServiceImpl service = new VoiceSessionTicketServiceImpl(
                store,
                protector(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SecureRandom(new byte[] {1, 2, 3, 4}));

        VoiceSessionTicketIssue issue = service.issue(
                binding(),
                DEVICE_ID);
        VoiceSessionTicketSnapshot consumed = service.consume(issue.ticket());

        assertThat(issue.ticket()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(issue.expiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(issue.toString()).doesNotContain(issue.ticket()).contains("ticket=redacted");
        assertThat(store.ticketTtl).isEqualTo(Duration.ofSeconds(30));
        assertThat(store.ticketHash.value()).isNotEqualTo(issue.ticket());
        assertThat(consumed.schemaVersion()).isEqualTo(2);
        assertThat(consumed.binding().userId()).isEqualTo(10001L);
        assertThat(consumed.binding().platform()).isEqualTo(VoiceClientPlatform.H5);
        assertThat(consumed.toString()).doesNotContain(DEVICE_ID);
        assertThatThrownBy(() -> service.consume(issue.ticket()))
                .isInstanceOfSatisfying(VoiceException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(VoiceErrorCode.VOICE_TICKET_INVALID));
    }

    @Test
    void rejectsLegacySchemaOneSnapshots() {
        assertThatThrownBy(() -> new VoiceSessionTicketSnapshot(
                1, binding(), NOW.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static VoiceTicketSecurityBinding binding() {
        return new VoiceTicketSecurityBinding(
                10001L,
                VoiceClientPlatform.H5,
                "A".repeat(43),
                "B".repeat(43),
                "C".repeat(43),
                "D".repeat(43),
                "E".repeat(43),
                "F".repeat(43),
                7L);
    }

    private static AuthSessionSecretProtector protector() {
        return new AuthSessionSecretProtector(new HmacSha256Identifier(
                "voice-ticket-test-secret-0123456789".getBytes()));
    }

    private static VoiceProperties properties() {
        return new VoiceProperties(
                true,
                "/ws/voice",
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                10,
                Duration.ofMinutes(5),
                Duration.ofMillis(1500),
                3,
                5,
                Duration.ofSeconds(90),
                List.of("https://localhost:5173"),
                URI.create("wss://127.0.0.1:7896/ws/transcribe"),
                "file:test.pem",
                Duration.ofSeconds(5),
                Duration.ofMinutes(2));
    }

    private static final class InMemoryStore implements VoiceSessionTicketStore {

        private HmacIdentifier ticketHash;
        private VoiceSessionTicketSnapshot snapshot;
        private Duration ticketTtl;

        @Override
        public void create(
                HmacIdentifier ticketHash,
                HmacIdentifier userRateHash,
                HmacIdentifier deviceRateHash,
                VoiceSessionTicketSnapshot snapshot,
                Duration ticketTtl,
                Duration rateWindow,
                int rateLimit) {
            this.ticketHash = ticketHash;
            this.snapshot = snapshot;
            this.ticketTtl = ticketTtl;
        }

        @Override
        public Optional<VoiceSessionTicketSnapshot> consume(HmacIdentifier requestedHash) {
            if (!requestedHash.equals(ticketHash) || snapshot == null) {
                return Optional.empty();
            }
            VoiceSessionTicketSnapshot consumed = snapshot;
            snapshot = null;
            return Optional.of(consumed);
        }
    }
}
