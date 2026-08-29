package com.example.temperate.service.bloom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private static final Path SCRIPT_ROOT = Path.of(
            "src/main/resources/lua/bloom/versioned-composite");

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

    @Test
    void versionedEngineUsesResourceScriptsAndGroupedCounterCommands()
            throws IOException {
        String source = Files.readString(GENERIC_ENGINE, StandardCharsets.UTF_8);
        String query = Files.readString(
                SCRIPT_ROOT.resolve("query.lua"), StandardCharsets.UTF_8);
        String add = Files.readString(
                SCRIPT_ROOT.resolve("add_batch.lua"), StandardCharsets.UTF_8);
        String remove = Files.readString(
                SCRIPT_ROOT.resolve("remove_batch.lua"), StandardCharsets.UTF_8);

        assertThat(source)
                .contains("lua/bloom/versioned-composite/")
                .contains("script(\"query.lua\", Long.class)")
                .contains("script(\"add_batch.lua\", Long.class)")
                .contains("script(\"remove_batch.lua\", Long.class)")
                .doesNotContain("GETRANGE")
                .doesNotContain("SETRANGE', key, offset");
        assertThat(query)
                .contains("BITFIELD_RO")
                .doesNotContain("GETRANGE");
        assertThat(add)
                .contains("HMGET")
                .contains("SMISMEMBER")
                .contains("BITFIELD_RO")
                .contains("BITFIELD")
                .doesNotContain("SISMEMBER")
                .doesNotContain("GETRANGE")
                .doesNotContain("SETRANGE");
        assertThat(remove)
                .contains("SMISMEMBER")
                .contains("BITFIELD_RO")
                .contains("BITFIELD")
                .doesNotContain("SISMEMBER")
                .doesNotContain("GETRANGE")
                .doesNotContain("SETRANGE");
    }

    @Test
    void productionCodeDoesNotCallLegacyCountingBloomBatchEntrypoints()
            throws IOException {
        List<String> sources;
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .toList();
        }

        assertThat(sources).allSatisfy(source -> assertThat(source)
                .doesNotContain(".addAllItems(")
                .doesNotContain(".addAllLongs(")
                .doesNotContain(".deleteAllItems(")
                .doesNotContain(".deleteAllLongs(")
                .doesNotContain(".existsAllItems(")
                .doesNotContain(".existsAllLongs("));
    }

    @Test
    void genericNamespaceRejectsBatchesAboveFiveHundred() {
        CountingBloomLayout layout = new CountingBloomLayout(
                1_000_000, 7, 1, 1_000_000);

        assertThatThrownBy(() -> new VersionedCompositeCountingBloomEngine.Namespace(
                        layout,
                        501,
                        16,
                        900_000,
                        "ait:test:bloom:control:v1",
                        "ait:test:bloom:lease:v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
