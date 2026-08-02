package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证异步 Generation 表、逻辑关联、幂等 CAS、恢复扫描和有界清理 SQL 契约。
 */
final class AiConversationGenerationPersistenceContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void schemaUsesLogicalRelationshipsAndRequiredRecoveryIndexes() throws IOException {
        String generationSchema = read("sql/011_create_ai_conversation_generation.sql");
        String payloadSchema = read("sql/012_create_ai_conversation_generation_payload.sql");
        String orphanChecks = read("sql/checks/ai_conversation_generation_orphans.sql");

        assertThat(generationSchema)
                .contains("ai_conversation_generation")
                .contains("UNIQUE (usage_id)")
                .contains("UNIQUE (idempotency_key_digest)")
                .contains("idx_ai_conversation_generation_recovery")
                .contains("idx_ai_conversation_generation_owner")
                .contains("idx_ai_conversation_generation_detached_due")
                .contains("uq_ai_conversation_generation_conversation_active")
                .contains("idx_ai_conversation_generation_model")
                .contains("COMMENT ON TABLE ai_conversation_generation")
                .doesNotContain("ai_conversation_generation_payload")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(payloadSchema)
                .contains("ai_conversation_generation_payload")
                .contains("idx_ai_conversation_generation_payload_message")
                .contains("conversation_message_id BIGINT NULL")
                .contains("context_generation VARCHAR(64) NULL")
                .contains("ephemeral_ordinal BIGINT NULL")
                .contains("chk_ai_conversation_generation_payload_context_cursor")
                .contains("COMMENT ON TABLE ai_conversation_generation_payload")
                .doesNotContain("CREATE TABLE IF NOT EXISTS ai_conversation_generation (")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(orphanChecks)
                .contains("LEFT JOIN userloginidentity")
                .contains("LEFT JOIN ai_conversation")
                .contains("LEFT JOIN ai_model_usage")
                .contains("LEFT JOIN ai_model")
                .contains("LEFT JOIN ai_conversation_message")
                .contains("PAYLOAD_WITHOUT_GENERATION");
    }

    @Test
    void mapperUsesExpectedStateUpdatesAndBoundedSkipLockedCleanup() throws IOException {
        String generation = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiConversationGenerationMapper.xml");
        String payload = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiConversationGenerationPayloadMapper.xml");

        assertThat(generation)
                .contains("claimQueued")
                .contains("observer_epoch = observer_epoch + 1")
                .contains("#{detachedAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}")
                .contains("observer_epoch = #{expectedEpoch")
                .contains("terminal_version = terminal_version + 1")
                .contains("findRecoveryCandidates")
                .contains("findTerminalCleanupCandidates")
                .contains("FOR UPDATE SKIP LOCKED")
                .doesNotContain("${");
        assertThat(payload)
                .contains("freezeTerminalEvidence")
                .contains("conversation_message_id IS NULL")
                .contains("bindContextCursor")
                .contains("deleteByGenerationIds")
                .doesNotContain("${");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-mapper"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
