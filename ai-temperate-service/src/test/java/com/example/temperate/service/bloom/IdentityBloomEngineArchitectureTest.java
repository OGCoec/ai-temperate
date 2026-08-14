package com.example.temperate.service.bloom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定身份 Bloom 只做领域映射、Redis I/O 统一位于通用版本化引擎的架构边界。
 */
final class IdentityBloomEngineArchitectureTest {

    private static final Path IDENTITY_STORE = Path.of(
            "src/main/java/com/example/temperate/service/auth/identity/bloom/store/impl/"
                    + "RedisIdentityPresenceBloomStore.java");
    private static final Path GENERIC_ENGINE = Path.of(
            "src/main/java/com/example/temperate/service/bloom/impl/"
                    + "RedisVersionedCompositeCountingBloomEngineImpl.java");

    @Test
    void identityStoreDelegatesWithoutDirectRedisIo() throws IOException {
        String source = Files.readString(IDENTITY_STORE, StandardCharsets.UTF_8);

        assertThat(source)
                .contains("VersionedCompositeCountingBloomEngine engine")
                .contains("RedisKeyFactory keyFactory")
                .doesNotContain("StringRedisTemplate")
                .doesNotContain("DefaultRedisScript")
                .doesNotContain("redisTemplate.execute");
    }

    @Test
    void genericEngineDoesNotDependOnIdentityDomainTypes() throws IOException {
        String source = Files.readString(GENERIC_ENGINE, StandardCharsets.UTF_8);

        assertThat(source)
                .contains("implements VersionedCompositeCountingBloomEngine")
                .contains("StringRedisTemplate")
                .doesNotContain("service.auth.identity")
                .doesNotContain("IdentityPresence");
    }
}
