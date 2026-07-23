package com.example.temperate.service.auth.session.authentication.domain;

/**
 * 表示认证成功后可安全传递给业务层的当前用户会话主体。
 *
 * <p>该类型只包含内部 ID、公共 ID 和显示名，不携带密码、令牌或其他可重放认证材料。</p>
 */
public record SessionPrincipal(
        long userId,
        String publicId,
        String displayName) {
}
