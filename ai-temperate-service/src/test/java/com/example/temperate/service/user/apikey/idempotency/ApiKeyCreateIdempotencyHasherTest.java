package com.example.temperate.service.user.apikey.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来确保 API Key 创建锁摘要同时绑定用户和 UUID，并保持可用于 Redis Key 的稳定受保护格式。
 */
final class ApiKeyCreateIdempotencyHasherTest {

    @Test
    void identifierIsStableAndChangesWithEitherInput() {
        ApiKeyCreateIdempotencyHasher hasher =
                new ApiKeyCreateIdempotencyHasher(new byte[32]);
        UUID key = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        var first = hasher.identify(17L, key);

        assertThat(first.value()).hasSize(43);
        assertThat(hasher.identify(17L, key)).isEqualTo(first);
        assertThat(hasher.identify(18L, key)).isNotEqualTo(first);
        assertThat(hasher.identify(
                17L,
                UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e")))
                .isNotEqualTo(first);
        assertThat(first.value()).doesNotContain(key.toString());
    }
}
