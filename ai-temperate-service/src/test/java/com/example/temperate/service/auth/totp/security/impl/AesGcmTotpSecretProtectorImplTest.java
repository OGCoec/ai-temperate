package com.example.temperate.service.auth.totp.security.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.auth.totp.config.TotpProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 TOTP 共享密钥密文的保密性、用户绑定和篡改检测边界。
 */
class AesGcmTotpSecretProtectorImplTest {

    private final AesGcmTotpSecretProtectorImpl protector =
            new AesGcmTotpSecretProtectorImpl(properties(), new SecureRandom());

    @Test
    void encryptsAndDecryptsForTheSameUser() {
        byte[] secret = new byte[32];
        secret[0] = 42;

        String encrypted = protector.encrypt(10001L, secret);

        assertThat(encrypted).startsWith("v1.v1.");
        assertThat(encrypted).doesNotContain(Base64.getEncoder().encodeToString(secret));
        assertThat(protector.decrypt(10001L, encrypted)).containsExactly(secret);
    }

    @Test
    void producesDifferentCiphertextForTheSameSecret() {
        byte[] secret = new byte[32];

        assertThat(protector.encrypt(10001L, secret))
                .isNotEqualTo(protector.encrypt(10001L, secret));
    }

    @Test
    void rejectsDifferentUserAndTamperedCiphertext() {
        String encrypted = protector.encrypt(10001L, new byte[32]);
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> protector.decrypt(10002L, encrypted))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.decrypt(10001L, tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TotpProperties properties() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return new TotpProperties(
                "AI Temperate",
                32,
                6,
                Duration.ofSeconds(30),
                1,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                5,
                new TotpProperties.Encryption(
                        "v1", Base64.getEncoder().encodeToString(key)));
    }
}
