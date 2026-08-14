package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 API Key 与模型授权映射的软删除数据库设计，避免后续实现引入物理删除、物理外键或可恢复密钥字段。
 */
final class UserApiKeyPersistenceContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void apiKeySchemaStoresOnlyDigestAndUsesStableOwnerCursorIndex() throws IOException {
        String schema = read("sql/014_create_user_api_key.sql");

        assertThat(schema)
                .contains("key_digest BYTEA NOT NULL")
                .contains("last_used_at TIMESTAMPTZ")
                .contains("login_identity_id,\n        created_at DESC,\n        id DESC")
                .contains("WHERE status IN (0, 1)")
                .doesNotContain("hmac_version")
                .doesNotContain("encryption_version")
                .doesNotContain("encrypted_key")
                .doesNotContain("DELETE FROM")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
    }

    @Test
    void modelGrantSchemaSupportsRevocationAndRestorationWithoutPhysicalDelete()
            throws IOException {
        String schema = read("sql/015_create_user_api_key_model.sql");

        assertThat(schema)
                .contains("PRIMARY KEY (user_api_key_id, ai_model_id)")
                .contains("status SMALLINT NOT NULL DEFAULT 1")
                .contains("created_at TIMESTAMPTZ NOT NULL")
                .contains("updated_at TIMESTAMPTZ NOT NULL")
                .contains("deleted_at TIMESTAMPTZ")
                .contains("status IN (0, 1)")
                .contains("status = 0 AND deleted_at IS NOT NULL")
                .contains("status = 1 AND deleted_at IS NULL")
                .doesNotContain("DELETE FROM")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
    }

    @Test
    void apiUsageSchemasNeverExposePhysicalDeleteOrRequestBodies() throws IOException {
        String usage = read("sql/016_create_ai_model_api_usage.sql");
        String detail = read("sql/017_create_ai_model_api_usage_detail.sql");

        assertThat(usage)
                .doesNotContain("DELETE FROM")
                .doesNotContain("request_body")
                .doesNotContain("response_body")
                .doesNotContain("login_identity_id")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(detail)
                .doesNotContain("DELETE FROM")
                .doesNotContain("request_body")
                .doesNotContain("response_body")
                .doesNotContain("login_identity_id")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
    }

    @Test
    void apiKeyAndUsageMapperXmlNeverExposePhysicalDelete() throws IOException {
        for (String path : java.util.List.of(
                "ai-temperate-mapper/src/main/resources/mapper/ai/UserApiKeyMapper.xml",
                "ai-temperate-mapper/src/main/resources/mapper/ai/UserApiKeyModelMapper.xml",
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelApiUsageMapper.xml",
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelApiUsageDetailMapper.xml")) {
            assertThat(read(path).toUpperCase(java.util.Locale.ROOT))
                    .as(path)
                    .doesNotContain("DELETE FROM")
                    .doesNotContain("FOREIGN KEY")
                    .doesNotContain("REFERENCES");
        }
    }

    @Test
    void reservationAuthorizationKeepsMissingGrantVisibleFor403Classification()
            throws IOException {
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/UserApiKeyMapper.xml");

        assertThat(mapper)
                .contains("LEFT JOIN user_api_key_model grant_record")
                .contains("grant_record.status AS mapping_status")
                .contains("FOR UPDATE OF key_record, profile, model")
                .doesNotContain("FOR UPDATE OF key_record, profile, grant_record");
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
