package com.example.temperate.service.user.voice.security;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.user.voice.VoiceClientPlatform;

/**
 * 表示签发 Voice Ticket v2 时从已完成 HTTP 拦截链取得的安全上下文。
 */
public record VoiceTicketIssueCommand(
        SessionPrincipal principal,
        VoiceClientPlatform platform,
        String deviceInstallationId,
        String rawRefreshToken,
        PreAuthAccess preAuthAccess) {
}
