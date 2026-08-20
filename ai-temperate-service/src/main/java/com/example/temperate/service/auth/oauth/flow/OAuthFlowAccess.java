package com.example.temperate.service.auth.oauth.flow;

/**
 * 表示客户端访问 OAuth 短时流程所需的原始令牌、设备安装标识和规范 IP。
 *
 * <p>该对象只存在于当前请求内，进入 Redis 前必须转换为用途隔离 HMAC。</p>
 */
public record OAuthFlowAccess(
        String rawFlowToken,
        String deviceInstallationId,
        String canonicalIp) {
}
