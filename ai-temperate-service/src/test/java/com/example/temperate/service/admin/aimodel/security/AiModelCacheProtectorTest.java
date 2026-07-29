package com.example.temperate.service.admin.aimodel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.admin.aimodel.cache.ProtectedAiModelCacheSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 模型缓存的 AES-GCM 往返、密文保密性及版本、AAD、密钥和篡改拒绝语义。
 */
final class AiModelCacheProtectorTest {

    private static final String CACHE_KEY = "ait:test:ai:model:v3:enabled";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void protectsWholeSnapshotWithoutLeakingModelText() {
        assertThat(AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION).isEqualTo(3);

        AiModelCacheProtector protector = new AiModelCacheProtector(KEY, new ObjectMapper());
        AiModelCacheSnapshot snapshot = snapshot();

        ProtectedAiModelCacheSnapshot protectedSnapshot = protector.protect(CACHE_KEY, snapshot);
        ProtectedAiModelCacheSnapshot secondWrite = protector.protect(CACHE_KEY, snapshot);

        assertThat(protectedSnapshot.envelope()).startsWith("v1.");
        assertThat(secondWrite.envelope()).isNotEqualTo(protectedSnapshot.envelope());
        assertThat(protectedSnapshot.envelope())
                .doesNotContain("gpt-5.5")
                .doesNotContain("openai")
                .doesNotContain("RESPONSES");
        assertThat(protector.unprotect(CACHE_KEY, protectedSnapshot.envelope()))
                .isEqualTo(snapshot);
    }

    @Test
    void rejectsTamperingWrongKeyWrongAadAndWrongVersion() {
        AiModelCacheProtector protector = new AiModelCacheProtector(KEY, new ObjectMapper());
        String envelope = protector.protect(CACHE_KEY, snapshot()).envelope();
        String tampered = envelope.substring(0, envelope.length() - 1)
                + (envelope.endsWith("A") ? "B" : "A");
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        AiModelCacheProtector wrongKey = new AiModelCacheProtector(
                Base64.getEncoder().encodeToString(otherKey),
                new ObjectMapper());

        assertThatThrownBy(() -> protector.unprotect(CACHE_KEY, tampered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wrongKey.unprotect(CACHE_KEY, envelope))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.unprotect(CACHE_KEY + ":other", envelope))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.unprotect(CACHE_KEY, "v2" + envelope.substring(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresCanonicalBase64ContainingExactlyThirtyTwoBytes() {
        assertThatThrownBy(() -> new AiModelCacheProtector("c2hvcnQ=", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiModelCacheProtector("not-base64", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AiModelCacheSnapshot snapshot() {
        return new AiModelCacheSnapshot(
                AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                List.of(new AiModelCacheEntry(
                        123L,
                        "gpt-5.5",
                        "openai",
                        "test model",
                        null,
                        List.of("chat"),
                        BigDecimal.ONE,
                        new BigDecimal("0.50000000"),
                        BigDecimal.TWO,
                        List.of(
                                AiModelCapabilityCode.RESPONSES,
                                AiModelCapabilityCode.IMAGE))));
    }
}
