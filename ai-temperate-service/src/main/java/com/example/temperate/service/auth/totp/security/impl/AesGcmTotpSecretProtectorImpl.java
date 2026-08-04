package com.example.temperate.service.auth.totp.security.impl;

import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.security.TotpSecretProtector;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 使用带用户用途绑定的 AES-256-GCM 信封保护 TOTP 共享密钥。
 *
 * <p>密文格式固定为“版本.KeyID.Nonce.Ciphertext”，Nonce 每次随机生成；AAD 绑定用户 ID，防止不同用户之间
 * 复制密文后仍能解密。该组件不记录明文、密文或加密主密钥。</p>
 */
@Component
public final class AesGcmTotpSecretProtectorImpl implements TotpSecretProtector {

    private static final String ENVELOPE_VERSION = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String keyId;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    @Autowired
    public AesGcmTotpSecretProtectorImpl(TotpProperties properties) {
        this(properties, new SecureRandom());
    }

    AesGcmTotpSecretProtectorImpl(
            TotpProperties properties,
            SecureRandom secureRandom) {
        TotpProperties valid = Objects.requireNonNull(
                properties, "properties must not be null");
        this.keyId = valid.encryption().activeKeyId();
        byte[] keyBytes = Base64.getDecoder().decode(
                valid.encryption().activeKeyBase64());
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("TOTP encryption key must contain 32 bytes.");
        }
        try {
            this.key = new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
        this.secureRandom = Objects.requireNonNull(
                secureRandom, "secureRandom must not be null");
    }

    @Override
    public String encrypt(long userId, byte[] secret) {
        byte[] validSecret = requireSecret(userId, secret);
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId));
            byte[] ciphertext = cipher.doFinal(validSecret);
            return String.join(
                    ".",
                    ENVELOPE_VERSION,
                    keyId,
                    ENCODER.encodeToString(nonce),
                    ENCODER.encodeToString(ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP secret encryption is unavailable.", exception);
        } finally {
            Arrays.fill(validSecret, (byte) 0);
        }
    }

    @Override
    public byte[] decrypt(long userId, String encryptedSecret) {
        if (userId <= 0
                || encryptedSecret == null
                || encryptedSecret.isBlank()
                || encryptedSecret.length() > 512) {
            throw invalid(null);
        }
        String[] parts = encryptedSecret.split("\\.", -1);
        if (parts.length != 4
                || !ENVELOPE_VERSION.equals(parts[0])
                || !keyId.equals(parts[1])) {
            throw invalid(null);
        }
        try {
            byte[] nonce = DECODER.decode(parts[2]);
            byte[] ciphertext = DECODER.decode(parts[3]);
            if (nonce.length != NONCE_BYTES
                    || !ENCODER.encodeToString(nonce).equals(parts[2])
                    || !ENCODER.encodeToString(ciphertext).equals(parts[3])) {
                throw invalid(null);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId));
            byte[] plaintext = cipher.doFinal(ciphertext);
            try {
                return requireSecret(userId, plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (AEADBadTagException exception) {
            throw invalid(exception);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw invalid(exception);
        }
    }

    private static byte[] requireSecret(long userId, byte[] secret) {
        if (userId <= 0 || secret == null || secret.length != 32) {
            throw new IllegalArgumentException("TOTP secret material is invalid.");
        }
        return secret.clone();
    }

    private static byte[] aad(long userId) {
        return ("totp-secret" + Character.toString(0) + userId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalid(Throwable cause) {
        return new IllegalArgumentException("TOTP encrypted secret is invalid.", cause);
    }
}
