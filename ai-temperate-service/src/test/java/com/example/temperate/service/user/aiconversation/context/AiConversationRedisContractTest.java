package com.example.temperate.service.user.aiconversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证 AI 会话 Redis Lua 保持 generation 隔离和不可滑动的绝对过期时间。
 */
final class AiConversationRedisContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void createSetsAbsoluteExpiryAndAppendNeverRenewsIt() throws IOException {
        String create = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/create_context.lua");
        String append = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/append_fields.lua");

        assertThat(create)
                .contains("PEXPIREAT")
                .contains("HSET")
                .contains("generation");
        assertThat(append)
                .contains("HGET")
                .contains("generation")
                .contains("HSET")
                .doesNotContain("EXPIRE")
                .doesNotContain("PEXPIRE");
    }

    @Test
    void compactionUsesGenerationAndDeletesOnlyExplicitFields() throws IOException {
        String compact = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/replace_compaction.lua");
        String store = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/impl/RedisAiConversationContextStore.java");

        assertThat(compact)
                .contains("HGET")
                .contains("generation")
                .contains("HLEN")
                .contains("maximumFields")
                .contains("HDEL")
                .contains("deleteCount")
                .contains("writeCount")
                .contains("KEYS[1]")
                .doesNotContain("SCAN")
                .doesNotContain("PEXPIRE");
        assertThat(store)
                .contains("compact:persistent")
                .contains("putCompactionChunked")
                .contains("selectedOrdinals.contains")
                .doesNotContain("ephemeralOrdinal(field) <= throughEphemeralOrdinal");
    }

    @Test
    void concurrencyLeaseIsAtomicAndDistinguishesBothLimits() throws IOException {
        String acquire = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/acquire_concurrency.lua");
        String renew = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/renew_concurrency.lua");
        String release = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/release_concurrency.lua");

        assertThat(acquire)
                .contains("ZREMRANGEBYSCORE")
                .contains("ZCARD', KEYS[1]")
                .contains("ZCARD', KEYS[2]")
                .contains("return 2")
                .contains("return 3")
                .contains("PEXPIREAT");
        assertThat(renew).contains("ZSCORE").contains("ZADD");
        assertThat(release).contains("ZREM").contains("KEYS[1]").contains("KEYS[2]");
    }

    @Test
    void interruptedStateIsPersistedWithoutExtendingContextTtl() throws IOException {
        String interrupted = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/save_ephemeral_interrupted.lua");

        assertThat(interrupted)
                .contains("generation")
                .contains("INTERRUPTED")
                .contains("interruptionSource")
                .contains("assistantChunkCount")
                .contains("estimatedContextTokens")
                .contains("contextRevision")
                .contains("maximumFields")
                .doesNotContain("EXPIRE")
                .doesNotContain("PEXPIRE");
    }

    @Test
    void v2SnapshotRejectsLegacySchemaAndKeepsCompactionTimestamp()
            throws IOException {
        String store = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/impl/RedisAiConversationContextStore.java");

        assertThat(store)
                .contains("private static final int SCHEMA_VERSION = 2")
                .contains("meta.schemaVersion() != SCHEMA_VERSION")
                .contains("lastCompactedAt");
    }

    @Test
    void persistedCommitEnforcesHashLimitAndAtomicallyWritesTokenMetaInsideLua()
            throws IOException {
        String commit = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/commit_turn.lua");
        String store = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/impl/RedisAiConversationContextStore.java");

        assertThat(commit)
                .contains("generation")
                .contains("contextRevision")
                .contains("redis.call('HSET', KEYS[1], 'meta', ARGV[3])")
                .contains("redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])")
                .contains("HLEN")
                .contains("HEXISTS")
                .contains("maximumFields")
                .contains("return -1")
                .doesNotContain("EXPIRE")
                .doesNotContain("PEXPIRE");
        assertThat(store)
                .contains("current.estimatedContextTokens()")
                .contains("estimatedTurnTokens")
                .contains("json(meta)");
    }

    @Test
    void contextEventRevisionOutlivesTheShortLivedCompactionState()
            throws IOException {
        String claim = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/claim_context_compaction.lua");
        String transition = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/transition_context_compaction.lua");
        String usage = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/publish_context_usage.lua");
        String store = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/compaction/impl/RedisAiConversationCompactionStateStore.java");

        assertThat(claim).contains("INCR', KEYS[2]").contains("ARGV[6]");
        assertThat(transition).contains("INCR', KEYS[2]").contains("ARGV[9]");
        assertThat(usage).contains("INCR', KEYS[2]").contains("ARGV[4]");
        assertThat(store)
                .contains("aiConversationContextEventRevisionKey")
                .contains("conversationProperties.contextTtl()")
                .contains("AiConversationCompactionOperation.idle(");
    }

    @Test
    void largeRebuildUsesBatchedStagingHashAndAtomicPromotion() throws IOException {
        String store = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/impl/RedisAiConversationContextStore.java");
        String createBuild = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/create_context_build.lua");
        String appendBuild = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/append_context_build.lua");
        String promoteBuild = read(
                "ai-temperate-service/src/main/resources/lua/ai-conversation/promote_context_build.lua");

        assertThat(store)
                .contains("createInBatches")
                .contains("writeBatches")
                .contains("APPEND_BUILD_SCRIPT")
                .doesNotContain("opsForHash().putAll(buildKey");
        assertThat(createBuild)
                .contains("generation")
                .contains("PEXPIREAT");
        assertThat(appendBuild)
                .contains("HGET")
                .contains("generation")
                .contains("HLEN")
                .contains("HSET")
                .doesNotContain("EXPIRE")
                .doesNotContain("PEXPIRE");
        assertThat(promoteBuild)
                .contains("HLEN")
                .contains("EXISTS")
                .contains("RENAME")
                .contains("PEXPIREAT");
    }

    @Test
    void generationMismatchSavesTheWholeBoundedAnswerAtTerminal() throws IOException {
        String response = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");
        String context = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/impl/AiConversationContextServiceImpl.java");

        assertThat(response)
                .contains("restartEphemeralAfterGenerationMismatch")
                .contains("state.answer.toString()")
                .contains("saveInterruptedTurn")
                .contains("AiConversationInterruptionSource")
                .contains("commitPersistedCache")
                .contains("contextStore.invalidate(conversationPublicId)");
        assertThat(context)
                .contains("并发重建后的会话上下文尚不可读取")
                .contains("for (int attempt = 0; attempt < 3; attempt++)")
                .contains("candidate.lastCompactedMessageId() >= databaseCheckpoint")
                .doesNotContain("orElse(rebuilt)");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
