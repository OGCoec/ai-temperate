package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 会话、用量与额度结算持久化层保留逻辑关联、批量读取和短事务所需的 SQL 契约。
 */
final class AiConversationPersistenceContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void usageDetailLinksConversationAndOptionalPersistedMessage() throws IOException {
        String schema = read("sql/008_create_ai_model_usage_detail.sql");
        String orphanCheck = read("sql/checks/ai_model_usage_detail_orphans.sql");

        assertThat(schema)
                .contains("conversation_id BYTEA NOT NULL")
                .contains("conversation_message_id BIGINT")
                .contains("OCTET_LENGTH(conversation_id) = 16")
                .contains("conversation_message_id IS NULL")
                .contains("idx_ai_model_usage_detail_conversation_id")
                .contains("idx_ai_model_usage_detail_message_id")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(orphanCheck)
                .contains("LEFT JOIN ai_conversation")
                .contains("LEFT JOIN ai_conversation_message")
                .contains("conversation_message_id IS NOT NULL")
                .contains("message.conversation_id <> detail.conversation_id");
    }

    @Test
    void messageQueriesUseBoundedSetReadsInsteadOfPerMessageIo() throws IOException {
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiConversationMessageMapper.xml");

        assertThat(mapper)
                .contains("<select id=\"findAfterMessageId\"")
                .contains("id &gt; #{afterMessageId")
                .contains("LIMIT #{limit")
                .contains("<select id=\"findCompactionRange\"")
                .contains("id &lt;= #{cutoffMessageId")
                .contains("ORDER BY id ASC")
                .doesNotContain("${");
    }

    @Test
    void originalAndForwardSchemasUseConversationAndMessageIdIndex()
            throws IOException {
        String schema = read("sql/010_create_ai_conversation_message.sql");
        String migration = read(
                "sql/migrations/011_add_ai_conversation_message_id_index.sql");

        assertThat(schema)
                .contains("idx_ai_conversation_message_conversation_id")
                .contains("conversation_id,")
                .contains("id ASC")
                .doesNotContain("idx_ai_conversation_message_conversation_created_id\n");
        assertThat(migration)
                .contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS")
                .doesNotContain("BEGIN;")
                .doesNotContain("DROP INDEX");
    }

    @Test
    void genericAttachmentsAndSidebarCursorRemainBoundedAndUnindexed()
            throws IOException {
        String conversationSchema = read("sql/009_create_ai_conversation.sql");
        String messageSchema = read("sql/010_create_ai_conversation_message.sql");
        String conversationMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiConversationMapper.xml");
        String messageMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiConversationMessageMapper.xml");

        assertThat(conversationSchema)
                .contains("title VARCHAR(80)")
                .contains("last_message_id BIGINT")
                .contains("idx_ai_conversation_active_user_last_message")
                .contains("WHERE is_active = TRUE")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(messageSchema)
                .contains("content_attachments JSONB")
                .contains("response_attachments JSONB")
                .contains("JSONB_ARRAY_LENGTH(content_attachments) > 0")
                .contains("JSONB_ARRAY_LENGTH(response_attachments) > 0")
                .doesNotContain("content_photos JSONB")
                .doesNotContain("question_photos JSONB")
                .doesNotContain("GIN (content_attachments)")
                .doesNotContain("GIN (response_attachments)")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(conversationMapper)
                .contains("<select id=\"findActivePage\"")
                .contains("(last_message_id, id) &lt;")
                .contains("ORDER BY last_message_id DESC, id DESC")
                .contains("LIMIT #{limit");
        assertThat(messageMapper)
                .contains("<select id=\"findOwnedHistoryPage\"")
                .contains("INNER JOIN ai_model_usage_detail")
                .contains("INNER JOIN ai_model_usage")
                .contains("INNER JOIN ai_model")
                .contains("ORDER BY message.id DESC")
                .contains("LIMIT #{limit");
    }

    @Test
    void expiredReservationsAreUpdatedInOneBoundedSkipLockedStatement()
            throws IOException {
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelUsageMapper.xml");

        assertThat(mapper)
                .contains("markExpiredReservationsForReconciliation")
                .contains("LIMIT #{batchSize")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("UPDATE ai_model_usage usage");
    }

    @Test
    void historicalSystemFailureRefundUsesBoundedBatchSqlWithoutPerRowIo()
            throws IOException {
        String usage = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelUsageMapper.xml");
        String detail = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelUsageDetailMapper.xml");
        String quota = read(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/UserMembershipQuotaMapper.xml");

        assertThat(usage)
                .contains("findSystemFailureRefundCandidatesForUpdate")
                .contains("LIMIT #{batchSize")
                .contains("FOR UPDATE OF usage SKIP LOCKED")
                .contains("markHistoricalSystemFailuresRefunded")
                .contains("billing_status = #{refundedStatus");
        assertThat(detail)
                .contains("finalizeHistoricalRefunds")
                .contains("settlement_delta_minor = -candidate.reserved_quota_minor");
        assertThat(quota)
                .contains("addHistoricalAiRefunds")
                .contains("SUM(refund_minor)")
                .contains("UPDATE user_membership_quota quota");
    }

    @Test
    void idempotencyLookupIsSerializedByTransactionAdvisoryLock()
            throws IOException {
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelUsageDetailMapper.xml");

        assertThat(mapper)
                .contains("<select id=\"acquireIdempotencyLock\"")
                .contains("resultType=\"int\"")
                .contains("pg_advisory_xact_lock")
                .contains("findByIdempotencyDigest");
    }

    @Test
    void quotaMapperSupportsPessimisticReservationAndAtomicUpdate() throws IOException {
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/UserMembershipQuotaMapper.xml");
        String normalizedMapper = mapper.replaceAll("\\s+", " ");

        assertThat(normalizedMapper)
                .contains("<select id=\"findByLoginIdentityIdForUpdate\"")
                .contains("FOR UPDATE")
                .contains("<update id=\"updateBalanceAndPeriod\"")
                .contains("quota_balance_minor = #{quotaBalanceMinor")
                .contains("quota_period_started_at = #{quotaPeriodStartedAt")
                .contains("quota_period_ends_at = #{quotaPeriodEndsAt");
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
