package com.example.temperate.model.auth.domain;

/**
 * 表示数据库中某个用户当前生效的 TOTP 启用状态和加密共享密钥。
 *
 * <p>该对象只允许在受控 TOTP 服务内部使用，不携带明文密钥，也不得进入用户资料响应或通用缓存。</p>
 */
public record TotpCredential(
        long identityId,
        boolean enabled,
        String encryptedSecret) {
}
