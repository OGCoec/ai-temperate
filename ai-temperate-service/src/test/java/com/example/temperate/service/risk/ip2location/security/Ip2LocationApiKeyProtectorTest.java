package com.example.temperate.service.risk.ip2location.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationKeyMaterial;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 IP2Location API Key 的确定性去重标识、随机 AES-GCM 密文和 AAD 防替换边界。
 */
class Ip2LocationApiKeyProtectorTest {

    private static final String MASTER_SECRET = Base64.getEncoder().encodeToString(
            "ip2location-test-master-secret-0123456789".getBytes());
    private static final Instant CREATED_AT = Instant.parse("2026-07-25T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void sameApiKeyKeepsIdentifierButUsesDifferentCiphertext() {
        Ip2LocationApiKeyProtector protector = protector();

        ProtectedIp2LocationKey first = protector.protect(
                "free-test-key-0001",
                Ip2LocationPlanType.FREE,
                CREATED_AT,
                EXPIRES_AT);
        ProtectedIp2LocationKey second = protector.protect(
                "free-test-key-0001",
                Ip2LocationPlanType.FREE,
                CREATED_AT,
                EXPIRES_AT);

        assertThat(second.keyId()).isEqualTo(first.keyId());
        assertThat(second.encryptedEnvelope()).isNotEqualTo(first.encryptedEnvelope());
        Ip2LocationKeyMaterial material =
                protector.unprotect(first.keyId(), first.encryptedEnvelope());
        assertThat(material.apiKey()).isEqualTo("free-test-key-0001");
        assertThat(material.maskedKey()).isEqualTo("fre****001");
        assertThat(material.planType()).isEqualTo(Ip2LocationPlanType.FREE);
    }

    @Test
    void shortApiKeyIsFullyMasked() {
        Ip2LocationApiKeyProtector protector = protector();

        ProtectedIp2LocationKey protectedKey = protector.protect(
                "12345678",
                Ip2LocationPlanType.FREE,
                CREATED_AT,
                EXPIRES_AT);

        Ip2LocationKeyMaterial material =
                protector.unprotect(protectedKey.keyId(), protectedKey.encryptedEnvelope());
        assertThat(material.maskedKey()).isEqualTo("****");
    }

    @Test
    void legacyEnvelopeIsRemaskedWithoutRedisMigration() {
        Ip2LocationApiKeyProtector protector = protector();
        HmacIdentifier legacyKeyId = HmacIdentifier.fromProtectedValue(
                "jJ43IwZEu3Wi6Dliow-4TN1MeRiHTcN4yEEtnYwz-cU");
        // 固定夹具包含旧版前四后四脱敏值，用于保证历史密文读取后不会继续扩大凭据暴露面。
        String legacyEnvelope = "v1.AQIDBAUGBwgJCgsM."
                + "zOENJe18zGVo5E9U8gnJTz9jbkanWtSnaAdjHYJOszYY9ZxLxjUUgA0O1Usn1KHh"
                + "oCFqhF5bFOx_HhlkgaJvd40b82OMYC6aourLvgpta7nV7Hx42DVy46RNrjLEy6kf"
                + "8wMN1KSPGglk3lhJzlOYz49RDeq2xsJIxB3eHDkfT3yH1GzF3wt8Z32eCiAxzKZ"
                + "4rjxFGtIVak_Blj7AT_7KMhPpEZHJTWqYlUX5lHBmujjt4DyR";

        Ip2LocationKeyMaterial material =
                protector.unprotect(legacyKeyId, legacyEnvelope);

        assertThat(material.apiKey()).isEqualTo("free-test-key-0001");
        assertThat(material.maskedKey()).isEqualTo("fre****001");
    }

    @Test
    void rejectsTamperedCiphertextAndWrongAadIdentifier() {
        Ip2LocationApiKeyProtector protector = protector();
        ProtectedIp2LocationKey protectedKey = protector.protect(
                "free-test-key-0002",
                Ip2LocationPlanType.FREE,
                CREATED_AT,
                EXPIRES_AT);
        String envelope = protectedKey.encryptedEnvelope();
        char replacement = envelope.endsWith("A") ? 'B' : 'A';
        String tampered = envelope.substring(0, envelope.length() - 1) + replacement;
        HmacIdentifier wrongIdentifier = HmacIdentifier.fromProtectedValue(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThatThrownBy(() -> protector.unprotect(protectedKey.keyId(), tampered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.unprotect(wrongIdentifier, envelope))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCiphertextProtectedByAnotherMasterKey() {
        Ip2LocationApiKeyProtector original = protector();
        ProtectedIp2LocationKey protectedKey = original.protect(
                "free-test-key-0003",
                Ip2LocationPlanType.FREE,
                CREATED_AT,
                EXPIRES_AT);
        String otherSecret = Base64.getEncoder().encodeToString(
                "different-test-master-secret-0123456789".getBytes());
        Ip2LocationApiKeyProtector other = new Ip2LocationApiKeyProtector(
                otherSecret,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                new IncrementingSecureRandom());

        assertThatThrownBy(() -> other.unprotect(
                        protectedKey.keyId(),
                        protectedKey.encryptedEnvelope()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Ip2LocationApiKeyProtector protector() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new Ip2LocationApiKeyProtector(
                MASTER_SECRET,
                objectMapper,
                new IncrementingSecureRandom());
    }

    /**
     * 为测试提供可重复但每次不同的 IV；生产代码仍使用系统 SecureRandom。
     */
    private static final class IncrementingSecureRandom extends SecureRandom {

        private int counter;

        @Override
        public void nextBytes(byte[] bytes) {
            counter++;
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (counter + index);
            }
        }
    }
}
