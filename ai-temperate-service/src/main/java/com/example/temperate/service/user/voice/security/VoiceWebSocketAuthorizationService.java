package com.example.temperate.service.user.voice.security;

import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketIssue;

/**
 * 定义 Voice Ticket 签发与 WebSocket Upgrade 前完整安全复核的共享业务边界。
 */
public interface VoiceWebSocketAuthorizationService {

    VoiceSessionTicketIssue issueTicket(VoiceTicketIssueCommand command);

    VoiceHandshakePrincipal authorize(VoiceHandshakeCommand command);
}
