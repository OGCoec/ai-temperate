package com.example.temperate.service.user.voice.security;

import com.example.temperate.service.user.voice.VoiceClientPlatform;

/**
 * 表示完成 Ticket、PreAuth、WebRTC、设备与登录 Session 复核后的 WebSocket 安全主体。
 */
public record VoiceHandshakePrincipal(
        long userId,
        String publicId,
        String displayName,
        VoiceClientPlatform platform) {

    public static final String ATTRIBUTE =
            VoiceHandshakePrincipal.class.getName() + ".principal";

    public VoiceHandshakePrincipal {
        if (userId <= 0 || publicId == null || publicId.isBlank()
                || displayName == null || displayName.isBlank() || platform == null) {
            throw new IllegalArgumentException("Voice handshake principal is invalid.");
        }
    }
}
