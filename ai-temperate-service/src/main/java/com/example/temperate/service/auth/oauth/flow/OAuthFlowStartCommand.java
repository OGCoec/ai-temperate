package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;

/**
 * 表示服务端已经解析平台、交互模式、设备和规范 IP 后的 OAuth 启动命令。
 */
public record OAuthFlowStartCommand(
        OAuthProvider provider,
        OAuthClientPlatform platform,
        OAuthInteractionMode interactionMode,
        String deviceInstallationId,
        String canonicalIp) {
}
