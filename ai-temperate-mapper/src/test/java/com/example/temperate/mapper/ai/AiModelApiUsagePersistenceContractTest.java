package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 API Key 模型用量、预扣详情及其无物理外键补偿机制符合已经批准的数据库契约。
 */
final class AiModelApiUsagePersistenceContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void coreUsageStoresFinalMeteringWithoutClientIdempotencyOrPriceRatios()
            throws IOException {
        String schema = read("sql/016_create_ai_model_api_usage.sql");

        assertThat(schema)
                .contains("CREATE TABLE ai_model_api_usage")
                .contains("id BIGINT GENERATED ALWAYS AS IDENTITY")
                .contains("key_digest BYTEA NOT NULL")
                .contains("ai_model_id BIGINT NOT NULL")
                .contains("charged_quota_minor BIGINT")
                .contains("idx_ai_model_api_usage_key_created_id")
                .contains("idx_ai_model_api_usage_model_created_id")
                .contains("idx_ai_model_api_usage_pending_created_id")
                .doesNotContain("idempotency_key_digest")
                .doesNotContain("ratio_snapshot")
                .doesNotContain("conversation_id")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
    }

    @Test
    void detailStoresOneToOneReservationAndSettlementDeltaOnly()
            throws IOException {
        String schema = read("sql/017_create_ai_model_api_usage_detail.sql");

        assertThat(schema)
                .contains("CREATE TABLE ai_model_api_usage_detail")
                .contains("usage_id BIGINT NOT NULL")
                .contains("UNIQUE (usage_id)")
                .contains("reserved_quota_minor BIGINT NOT NULL")
                .contains("settlement_delta_minor BIGINT")
                .contains("vendor_snapshot VARCHAR(128) NOT NULL")
                .contains("is_stream BOOLEAN NOT NULL")
                .doesNotContain("idempotency_key_digest")
                .doesNotContain("ratio_snapshot")
                .doesNotContain("conversation_id")
                .doesNotContain("conversation_message_id")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
    }

    @Test
    void logicalRelationshipsHaveOfflineOrphanChecks() throws IOException {
        String usageCheck = read("sql/checks/ai_model_api_usage_orphans.sql");
        String detailCheck = read("sql/checks/ai_model_api_usage_detail_orphans.sql");

        assertThat(usageCheck)
                .contains("LEFT JOIN user_api_key")
                .contains("LEFT JOIN ai_model")
                .contains("missing_user_api_key")
                .contains("missing_ai_model");
        assertThat(detailCheck)
                .contains("LEFT JOIN ai_model_api_usage")
                .contains("LEFT JOIN ai_model_api_usage_detail")
                .contains("missing_usage")
                .contains("missing_detail");
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
