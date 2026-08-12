package com.example.temperate.service.user.voice.security;

import com.example.temperate.service.user.voice.VoiceClientPlatform;

/**
 * 表示返回 101 前进行 Voice WebSocket 授权所需的最小握手材料。
 */
public record VoiceHandshakeCommand(
        String rawTicket,
        VoiceClientPlatform platform,
        boolean originPresent,
        String currentHttpIp) {
}
