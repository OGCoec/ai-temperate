package com.example.temperate.service.user.profile.cache.security;

import com.example.temperate.common.redis.key.EncryptedRedisId;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 使用独立 AES-256-KWP 密钥把内部用户 ID 转换为确定且可逆的 Redis Key 标识。
 *
 * <p>KWP 对相同八字节明文产生相同密文，满足按用户稳定定位缓存的要求，并校验密文完整性；该组件只保护
 * Redis Key 中的标识，不加密用户资料 Value，也不会把密钥、明文 ID 或密文写入日志。</p>
 */
public final class UserProfileCacheIdProtector {

    private static final String TRANSFORMATION = "AES/KWP/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int CIPHERTEXT_BYTES = 16;

    private final SecretKeySpec encryptionKey;

    public UserProfileCacheIdProtector(String encryptionKeyBase64) {
        byte[] keyBytes = decodeKey(encryptionKeyBase64);
        try {
            this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * 将正数内部 ID 按八字节大端序加密为二十二字符 Base64URL 标识。
     */
    public EncryptedRedisId protect(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("User profile cache ID requires a positive user ID.");
        }
        byte[] plaintext = ByteBuffer.allocate(Long.BYTES).putLong(userId).array();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
            byte[] ciphertext = cipher.doFinal(plaintext);
            if (ciphertext.length != CIPHERTEXT_BYTES) {
                throw new IllegalStateException(
                        "User profile cache ID encryption returned an unexpected length.");
            }
            return new EncryptedRedisId(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "User profile cache ID encryption failed.", exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * 解密并校验缓存标识，供未来受控诊断能力复用；普通资料读取不需要执行反向解密。
     */
    public long restore(EncryptedRedisId encryptedId) {
        if (encryptedId == null) {
            throw new IllegalArgumentException("Encrypted user profile cache ID is required.");
        }
        byte[] plaintext = null;
        try {
            byte[] ciphertext = Base64.getUrlDecoder().decode(encryptedId.value());
            if (ciphertext.length != CIPHERTEXT_BYTES) {
                throw new IllegalArgumentException(
                        "Encrypted user profile cache ID has an invalid length.");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey);
            plaintext = cipher.doFinal(ciphertext);
            if (plaintext.length != Long.BYTES) {
                throw new IllegalArgumentException(
                        "Encrypted user profile cache ID has an invalid payload.");
            }
            long userId = ByteBuffer.wrap(plaintext).getLong();
            if (userId <= 0 || !protect(userId).equals(encryptedId)) {
                throw new IllegalArgumentException(
                        "Encrypted user profile cache ID is not canonical.");
            }
            return userId;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Encrypted user profile cache ID is invalid.", exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static byte[] decodeKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != KEY_BYTES
                    || !Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException("Non-canonical AES-256 key.");
            }
            return decoded;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException(
                    "User profile cache ID key must be canonical Base64 containing 32 bytes.",
                    exception);
        }
    }
}
