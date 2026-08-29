package com.example.temperate.service.user.aiconversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来静态验证 AI 会话 Redis Lua 的 generation 隔离、分块集合命令和不可滑动绝对过期时间。
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
                .contains("HMGET")
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
                .contains("HMGET")
                .contains("generation")
                .contains("HLEN")
                .contains("maximumFields")
                .contains("HDEL")
                .contains("delete_count")
                .contains("write_count")
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
                .contains("hset_chunked(KEYS[1]")
                .contains("hmget_chunked(KEYS[1]")
                .contains("HLEN")
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
                .contains("MAX_FIELDS_PER_BATCH = 128")
                .contains("MAX_BUILD_COMMAND_BYTES = 256 * 1024")
                .contains("executePipelined")
                .doesNotContain("opsForHash().putAll(buildKey");
        assertThat(createBuild)
                .contains("generation")
                .contains("PEXPIREAT");
        assertThat(appendBuild)
                .contains("HMGET")
                .contains("generation")
                .contains("HLEN")
                .contains("hset_chunked")
                .doesNotContain("EXPIRE")
                .doesNotContain("PEXPIRE");
        assertThat(promoteBuild)
                .contains("HLEN")
                .contains("EXISTS")
                .contains("RENAME")
                .contains("PEXPIREAT");
    }

    @Test
    void fieldHeavyScriptsUseOneRedisCallPerOneHundredTwentyEightFields()
            throws IOException {
        String[] names = {
            "append_fields.lua",
            "append_context_build.lua",
            "commit_turn.lua",
            "replace_compaction.lua",
            "save_ephemeral_interrupted.lua",
            "create_context.lua",
            "start_ephemeral.lua"
        };
        for (String name : names) {
            String source = read(
                    "ai-temperate-service/src/main/resources/lua/ai-conversation/" + name);
            assertThat(source)
                    .as(name)
                    .contains("MAX_FIELDS_PER_CALL = 128")
                    .doesNotContain("redis.call('HEXISTS', KEYS[1]")
                    .doesNotContain("redis.call('HDEL', KEYS[1], ARGV[index])")
                    .doesNotContain("redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])");
        }
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
