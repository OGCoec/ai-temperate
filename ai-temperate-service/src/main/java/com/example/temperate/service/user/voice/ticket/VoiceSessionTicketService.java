package com.example.temperate.service.user.voice.ticket;

import com.example.temperate.service.user.voice.VoiceClientPlatform;

/**
 * 为已认证用户签发并消费一次性语音 WebSocket 票据。
 */
public interface VoiceSessionTicketService {

    VoiceSessionTicketIssue issue(
            long userId,
            VoiceClientPlatform platform,
            String deviceInstallationId);

    VoiceSessionTicketSnapshot consume(String rawTicket);
}
