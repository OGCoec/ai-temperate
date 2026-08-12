package com.example.temperate.service.user.voice.ticket.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketIssue;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketService;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketSnapshot;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketStore;
import com.example.temperate.service.user.voice.ticket.VoiceTicketSecurityBinding;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 使用强随机数签发三十秒有效的语音票据，并通过 HMAC 后的 Redis Key 保证单次消费。
 *
 * <p>原始票据只返回给当前请求且不会进入日志或 Redis Key；用户和设备限流与票据创建在同一 Lua 中完成。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public final class VoiceSessionTicketServiceImpl implements VoiceSessionTicketService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final VoiceSessionTicketStore store;
    private final AuthSessionSecretProtector protector;
    private final VoiceProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public VoiceSessionTicketServiceImpl(
            VoiceSessionTicketStore store,
            AuthSessionSecretProtector protector,
            VoiceProperties properties,
            Clock clock,
            @Qualifier("voiceTicketSecureRandom") SecureRandom secureRandom) {
        this.store = Objects.requireNonNull(store);
        this.protector = Objects.requireNonNull(protector);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    @Override
    public VoiceSessionTicketIssue issue(
            VoiceTicketSecurityBinding binding,
            String rawDeviceInstallationId) {
        VoiceTicketSecurityBinding validBinding = Objects.requireNonNull(binding);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ticketTtl());
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawTicket = ENCODER.encodeToString(randomBytes);

        HmacIdentifier ticketHash = protector.voiceTicket(rawTicket);
        HmacIdentifier userHash = protector.voiceTicketUser(validBinding.userId());
        HmacIdentifier deviceHash = protector.voiceTicketDevice(rawDeviceInstallationId);
        store.create(
                ticketHash,
                userHash,
                deviceHash,
                new VoiceSessionTicketSnapshot(
                        2,
                        validBinding,
                        expiresAt),
                properties.ticketTtl(),
                properties.ticketRateWindow(),
                properties.ticketRateLimit());
        return new VoiceSessionTicketIssue(rawTicket, expiresAt);
    }

    @Override
    public VoiceSessionTicketSnapshot consume(String rawTicket) {
        HmacIdentifier ticketHash;
        try {
            ticketHash = protector.voiceTicket(rawTicket);
        } catch (IllegalArgumentException exception) {
            throw invalidTicket();
        }
        VoiceSessionTicketSnapshot snapshot = store.consume(ticketHash)
                .orElseThrow(VoiceSessionTicketServiceImpl::invalidTicket);
        if (snapshot.schemaVersion() != 2 || !snapshot.expiresAt().isAfter(clock.instant())) {
            throw invalidTicket();
        }
        return snapshot;
    }

    private static VoiceException invalidTicket() {
        return new VoiceException(
                VoiceErrorCode.VOICE_TICKET_INVALID,
                "语音连接票据无效或已经过期。",
                false);
    }
}
