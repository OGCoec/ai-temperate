package com.example.temperate.service.auth.totp.security;

/**
 * 定义 TOTP 共享密钥在数据库和 Redis 之间可逆加密存储的安全边界。
 */
public interface TotpSecretProtector {

    String encrypt(long userId, byte[] secret);

    byte[] decrypt(long userId, String encryptedSecret);
}
