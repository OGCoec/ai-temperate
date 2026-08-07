package com.example.temperate.service.user.voice.ticket;

import java.time.Instant;

/**
 * 表示返回给已认证客户端的一次性语音 WebSocket 票据。
 */
public record VoiceSessionTicketIssue(String ticket, Instant expiresAt) {

    @Override
    public String toString() {
        return "VoiceSessionTicketIssue[ticket=redacted, expiresAt=" + expiresAt + "]";
    }
}
