package com.example.temperate.service.user.profile.cache.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证用户资料缓存 ID 使用确定性 AES-256-KWP 加密并保持可逆和防篡改边界。
 */
final class UserProfileCacheIdProtectorTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString(
                    "0123456789abcdef0123456789abcdef"
                            .getBytes(StandardCharsets.UTF_8));

    @Test
    void deterministicallyProtectsAndRestoresPositiveUserId() {
        UserProfileCacheIdProtector protector = new UserProfileCacheIdProtector(KEY);

        var first = protector.protect(10001L);
        var second = protector.protect(10001L);

        assertThat(first).isEqualTo(second);
        assertThat(first.value()).hasSize(22);
        assertThat(first.value()).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(first.value()).doesNotContain("10001");
        assertThat(protector.restore(first)).isEqualTo(10001L);
    }

    @Test
    void producesDifferentIdentifiersForDifferentUsers() {
        UserProfileCacheIdProtector protector = new UserProfileCacheIdProtector(KEY);

        assertThat(protector.protect(10001L))
                .isNotEqualTo(protector.protect(10002L));
    }

    @Test
    void rejectsInvalidIdsKeysAndTamperedCiphertext() {
        UserProfileCacheIdProtector protector = new UserProfileCacheIdProtector(KEY);
        var protectedId = protector.protect(10001L);
        char replacement = protectedId.value().charAt(0) == 'A' ? 'B' : 'A';
        var tampered = new com.example.temperate.common.redis.key.EncryptedRedisId(
                replacement + protectedId.value().substring(1));

        assertThatThrownBy(() -> protector.protect(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.protect(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.restore(tampered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfileCacheIdProtector(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new UserProfileCacheIdProtector(
                Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalStateException.class);
    }
}
