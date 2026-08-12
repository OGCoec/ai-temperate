package com.example.temperate.service.user.voice.ticket;


/**
 * 为已认证用户签发并消费一次性语音 WebSocket 票据。
 */
public interface VoiceSessionTicketService {

    VoiceSessionTicketIssue issue(
            VoiceTicketSecurityBinding binding,
            String rawDeviceInstallationId);

    VoiceSessionTicketSnapshot consume(String rawTicket);
}
