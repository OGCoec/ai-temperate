package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.temperate.mapper.typehandler.PostgreSqlUuidTypeHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
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
                .contains("id BYTEA NOT NULL")
                .contains("CHECK (OCTET_LENGTH(id) = 16)")
                .contains("key_digest BYTEA NOT NULL")
                .contains("create_idempotency_key UUID")
                .contains("CREATE UNIQUE INDEX uk_user_api_key_create_idempotency_key")
                .contains("WHERE create_idempotency_key IS NOT NULL")
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
                .contains("user_api_key_id BYTEA NOT NULL")
                .contains("CHECK (OCTET_LENGTH(user_api_key_id) = 16)")
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
    void hybridIdsAreExplicitBinaryParametersWithoutGeneratedKeys() throws IOException {
        for (String path : java.util.List.of(
                "ai-temperate-mapper/src/main/resources/mapper/ai/UserApiKeyMapper.xml",
                "ai-temperate-mapper/src/main/resources/mapper/ai/UserApiKeyModelMapper.xml",
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelApiUsageMapper.xml",
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelApiUsageDetailMapper.xml")) {
            assertThat(read(path))
                    .as(path)
                    .contains("jdbcType=BINARY")
                    .doesNotContain("useGeneratedKeys")
                    .doesNotContain("keyProperty=\"id\"");
        }
    }

    @Test
    void apiKeyMapperUsesUuidConflictHandlingWithoutAbortingTheTransaction()
            throws IOException {
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/UserApiKeyMapper.xml");
        String handler =
                "com.example.temperate.mapper.typehandler.PostgreSqlUuidTypeHandler";
        String handlerSource = read(
                "ai-temperate-mapper/src/main/java/com/example/temperate/mapper/typehandler/"
                        + "PostgreSqlUuidTypeHandler.java");

        assertThat(mapper)
                .contains("createIdempotencyKey")
                .contains("create_idempotency_key")
                .contains("javaType=\"java.util.UUID\"")
                .contains("typeHandler=\"" + handler + "\"")
                .contains("typeHandler=" + handler)
                .contains("ON CONFLICT (create_idempotency_key)")
                .contains("WHERE create_idempotency_key IS NOT NULL")
                .contains("DO NOTHING")
                .contains("findByCreateIdempotencyKey");
        assertThat(countOccurrences(mapper, handler)).isEqualTo(3);
        assertThat(handlerSource)
                .contains("extends BaseTypeHandler<UUID>")
                .contains("statement.setObject(index, parameter, Types.OTHER)")
                .contains("resultSet.getObject(columnName)")
                .contains("resultSet.getObject(columnIndex)")
                .contains("statement.getObject(columnIndex)");
    }

    @Test
    void apiKeyMapperXmlParsesWithThePostgreSqlUuidTypeHandler() throws IOException {
        String resource = "mapper/ai/UserApiKeyMapper.xml";
        Configuration configuration = new Configuration();
        try (InputStream input = Files.newInputStream(PROJECT_ROOT.resolve(
                "ai-temperate-mapper/src/main/resources/" + resource))) {
            assertThatCode(() -> new XMLMapperBuilder(
                    input,
                    configuration,
                    resource,
                    configuration.getSqlFragments()).parse())
                    .doesNotThrowAnyException();
        }

        var idempotencyMapping = configuration
                .getResultMap("com.example.temperate.mapper.ai.UserApiKeyMapper.UserApiKeyResultMap")
                .getResultMappings()
                .stream()
                .filter(mapping -> "createIdempotencyKey".equals(mapping.getProperty()))
                .findFirst()
                .orElseThrow();
        assertThat(idempotencyMapping.getTypeHandler())
                .isInstanceOf(PostgreSqlUuidTypeHandler.class);
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
        // Git 在 Windows 工作树中可能把 LF 转为 CRLF；契约只比较 SQL 结构，不应依赖检出平台的换行格式。
        return Files.readString(PROJECT_ROOT.resolve(relativePath), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static int countOccurrences(String source, String expected) {
        return (source.length() - source.replace(expected, "").length()) / expected.length();
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
