package com.example.temperate.service.user.voice.ticket;

import java.time.Instant;

/**
 * 表示 Redis 中短期保存并与用户、平台和设备绑定的语音票据快照。
 */
public record VoiceSessionTicketSnapshot(
        int schemaVersion,
        VoiceTicketSecurityBinding binding,
        Instant expiresAt) {

    public VoiceSessionTicketSnapshot {
        if (schemaVersion != 2 || binding == null || expiresAt == null) {
            throw new IllegalArgumentException("Voice session ticket snapshot is invalid.");
        }
    }

    @Override
    public String toString() {
        return "VoiceSessionTicketSnapshot[schemaVersion=" + schemaVersion
                + ", binding=redacted, expiresAt=" + expiresAt + "]";
    }
}
