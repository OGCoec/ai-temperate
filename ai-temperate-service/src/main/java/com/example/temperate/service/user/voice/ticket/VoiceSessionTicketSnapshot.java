package com.example.temperate.service.user.voice.ticket;

import com.example.temperate.service.user.voice.VoiceClientPlatform;
import java.time.Instant;

/**
 * 表示 Redis 中短期保存并与用户、平台和设备绑定的语音票据快照。
 */
public record VoiceSessionTicketSnapshot(
        int schemaVersion,
        long userId,
        VoiceClientPlatform platform,
        String deviceInstallationId,
        Instant expiresAt) {

    public VoiceSessionTicketSnapshot {
        if (schemaVersion != 1 || userId <= 0 || platform == null
                || deviceInstallationId == null || deviceInstallationId.isBlank()
                || expiresAt == null) {
            throw new IllegalArgumentException("Voice session ticket snapshot is invalid.");
        }
    }

    @Override
    public String toString() {
        return "VoiceSessionTicketSnapshot[schemaVersion=" + schemaVersion
                + ", userId=redacted, platform=" + platform
                + ", deviceInstallationId=redacted, expiresAt=" + expiresAt + "]";
    }
}
