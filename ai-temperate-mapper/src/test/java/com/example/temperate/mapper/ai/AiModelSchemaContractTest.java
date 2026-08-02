package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证 AI 模型、图标与能力建表脚本保留逻辑关联、无物理外键和孤儿检查契约。
 */
final class AiModelSchemaContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void aiModelUsesApplicationIdAndBooleanStatus() throws IOException {
        String sql = read("sql/003_create_ai_model.sql");

        assertThat(sql)
                .contains("id BIGINT NOT NULL")
                .doesNotContain("id BIGINT GENERATED ALWAYS AS IDENTITY")
                .contains("cached_input_ratio NUMERIC(20, 8) NOT NULL DEFAULT 1")
                .contains("CHECK (cached_input_ratio >= 0)")
                .contains("COMMENT ON COLUMN ai_model.cached_input_ratio")
                .contains("is_enabled BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("created_at DATE NOT NULL DEFAULT CURRENT_DATE")
                .contains("NEW.updated_at = CURRENT_DATE");
    }

    @Test
    void aiModelTokenLimitsUseNullablePairedExactDecimalKMultiples() throws IOException {
        String sql = read("sql/003_create_ai_model.sql");
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml");

        assertThat(sql)
                .contains("context_window_tokens BIGINT")
                .contains("max_output_tokens BIGINT")
                .doesNotContain("context_window_tokens BIGINT NOT NULL")
                .doesNotContain("max_output_tokens BIGINT NOT NULL")
                .contains("context_window_tokens IS NULL")
                .contains("max_output_tokens IS NULL")
                .contains("context_window_tokens <= 2147483647000")
                .contains("max_output_tokens <= 2147483647000")
                .contains("MOD(context_window_tokens, 1000) = 0")
                .contains("MOD(max_output_tokens, 1000) = 0")
                .contains("max_output_tokens <= context_window_tokens")
                .contains("COMMENT ON COLUMN ai_model.context_window_tokens")
                .contains("COMMENT ON COLUMN ai_model.max_output_tokens")
                .doesNotContain("ON ai_model (context_window_tokens")
                .doesNotContain("ON ai_model (max_output_tokens");
        assertThat(mapper)
                .contains("<result property=\"contextWindowTokens\" column=\"context_window_tokens\"/>")
                .contains("<result property=\"maxOutputTokens\" column=\"max_output_tokens\"/>")
                .contains("model.context_window_tokens AS context_window_tokens")
                .contains("model.max_output_tokens AS max_output_tokens")
                .contains("#{contextWindowTokens,jdbcType=BIGINT}")
                .contains("#{maxOutputTokens,jdbcType=BIGINT}")
                .contains("#{model.contextWindowTokens,jdbcType=BIGINT}")
                .contains("#{model.maxOutputTokens,jdbcType=BIGINT}");
    }

    @Test
    void tokenKiloUnitMigrationPreservesConfiguredKValuesAndInvalidatesEtags()
            throws IOException {
        String migration = read("sql/migrations/014_migrate_ai_model_token_kilo_unit.sql");

        assertThat(migration)
                .contains("BEGIN;")
                .contains("ADD COLUMN IF NOT EXISTS context_window_tokens BIGINT")
                .contains("ADD COLUMN IF NOT EXISTS max_output_tokens BIGINT")
                .contains("DROP CONSTRAINT IF EXISTS chk_ai_model_token_limits_configured_together")
                .contains("DROP CONSTRAINT IF EXISTS chk_ai_model_context_window_tokens")
                .contains("DROP CONSTRAINT IF EXISTS chk_ai_model_max_output_tokens")
                .contains("DROP CONSTRAINT IF EXISTS chk_ai_model_output_within_context_window")
                .contains("context_window_tokens = context_window_tokens / 1024 * 1000")
                .contains("max_output_tokens = max_output_tokens / 1024 * 1000")
                .contains("row_version = row_version + 1")
                .contains("is_enabled = FALSE")
                .contains("chk_ai_model_token_limits_configured_together")
                .contains("chk_ai_model_output_within_context_window")
                .contains("context_window_tokens <= 2147483647000")
                .contains("max_output_tokens <= 2147483647000")
                .contains("MOD(context_window_tokens, 1000) = 0")
                .contains("MOD(max_output_tokens, 1000) = 0")
                .contains("COMMIT;");
    }

    @Test
    void capabilityTableUsesApplicationIdWhitelistAndLogicalAssociation() throws IOException {
        String sql = read("sql/004_create_ai_model_capability.sql");
        String orphanCheck = read("sql/checks/ai_model_capability_orphans.sql");

        assertThat(sql)
                .contains("id BIGINT NOT NULL")
                .doesNotContain("id BIGINT GENERATED ALWAYS AS IDENTITY")
                .contains("UNIQUE (ai_model_id, capability_code)")
                .contains("CHAT_COMPLETIONS")
                .contains("RESPONSES")
                .contains("'WEB_SEARCH'")
                .contains("'IMAGE'")
                .contains("'VIDEO'")
                .contains("'AUDIO'")
                .contains("idx_ai_model_capability_ai_model_id")
                .contains("idx_ai_model_capability_code_model_id")
                .contains("capability_code ASC,")
                .contains("ai_model_id ASC")
                .doesNotContain("'IMAGE_GENERATION'")
                .doesNotContain("'VIDEO_GENERATION'")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(orphanCheck)
                .contains("LEFT JOIN ai_model")
                .contains("WHERE model.id IS NULL");
    }

    @Test
    void webSearchCapabilityMigrationExtendsOnlyTheCapabilityWhitelist()
            throws IOException {
        String migration = read(
                "sql/migrations/015_add_ai_model_web_search_capability.sql");

        assertThat(migration)
                .contains("BEGIN;")
                .contains("DROP CONSTRAINT IF EXISTS chk_ai_model_capability_code")
                .contains("ADD CONSTRAINT chk_ai_model_capability_code")
                .contains("'CHAT_COMPLETIONS'")
                .contains("'RESPONSES'")
                .contains("'WEB_SEARCH'")
                .contains("'IMAGE'")
                .contains("'VIDEO'")
                .contains("'AUDIO'")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES")
                .contains("COMMIT;");
    }

    @Test
    void iconTableKeepsMinimalSourceMetadataAndLogicalAssociation() throws IOException {
        String modelSql = read("sql/003_create_ai_model.sql");
        String iconSql = read("sql/006_create_ai_model_icon.sql");
        String orphanCheck = read("sql/checks/ai_model_icon_orphans.sql");
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml");

        assertThat(modelSql)
                .contains("icon_id BIGINT")
                .contains("idx_ai_model_icon_id")
                .doesNotContain("icon VARCHAR");
        assertThat(iconSql)
                .contains("CREATE TABLE ai_model_icon")
                .contains("id BIGINT NOT NULL")
                .doesNotContain("id BIGINT GENERATED ALWAYS AS IDENTITY")
                .contains("object_key VARCHAR(1024)")
                .contains("LOWER(icon_name)")
                .contains("UNIQUE (object_key)")
                .contains("COMMENT ON COLUMN ai_model_icon.object_key")
                .doesNotContain("source_type")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
        assertThat(orphanCheck)
                .contains("LEFT JOIN ai_model_icon")
                .contains("WHERE model.icon_id IS NOT NULL")
                .contains("icon.id IS NULL");
        assertThat(mapper)
                .contains("LEFT JOIN ai_model_icon icon")
                .contains("icon.icon_url AS icon")
                .contains("model.icon_id AS icon_id");
    }

    @Test
    void modelPageQueryDelegatesPaginationAndOrderingToPageHelper() throws IOException {
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml");
        String pageQuery = mapper.substring(
                mapper.indexOf("<select id=\"findPage\""),
                mapper.indexOf("</select>", mapper.indexOf("<select id=\"findPage\"")));

        assertThat(pageQuery)
                .doesNotContain("cursorId")
                .doesNotContain("ORDER BY")
                .doesNotContain("LIMIT")
                .contains("FROM ai_model");
    }

    @Test
    void schemaKeepsBothPageHelperSortIndexes() throws IOException {
        String sql = read("sql/003_create_ai_model.sql");

        assertThat(sql)
                .contains("ON ai_model (input_ratio ASC, output_ratio ASC, model_name ASC)")
                .contains("ON ai_model (output_ratio ASC, input_ratio ASC, model_name ASC)");
    }

    @Test
    void modelNameTokensKeepJsonbSchemaAndDocumentHyphenSplitting() throws IOException {
        String sql = read("sql/003_create_ai_model.sql");

        assertThat(sql)
                .contains("model_name_tokens JSONB NOT NULL DEFAULT '[]'::JSONB")
                .contains("CHECK (JSONB_TYPEOF(model_name_tokens) = 'array')")
                .contains("USING GIN (model_name_tokens)")
                .contains("模型名称经 Java 按 ASCII 横杠切分后的 JSONB 字符串数组")
                .doesNotContain("模型名称经 Java IK 分词后的 JSONB 字符串数组");
    }

    @Test
    void catalogFiltersUseJsonbContainmentAndExactVendorSearch() throws IOException {
        String migration = read("sql/003_create_ai_model.sql");
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml");

        assertThat(mapper)
                .contains("model.model_name_tokens @>")
                .contains("model.description_tokens @>")
                .contains("CAST(#{modelNameTokensJson,jdbcType=VARCHAR} AS JSONB)")
                .contains("CAST(#{descriptionTokensJson,jdbcType=VARCHAR} AS JSONB)")
                .contains("LOWER(model.vendor) = #{vendorExact,jdbcType=VARCHAR}")
                .contains("is_enabled = #{enabled,jdbcType=BOOLEAN}")
                .doesNotContain("LOWER(model.model_name) LIKE")
                .doesNotContain("LOWER(model.vendor) LIKE")
                .doesNotContain("${keyword}");
        assertThat(migration)
                .doesNotContain("CREATE INDEX CONCURRENTLY")
                .contains("LOWER(vendor) varchar_pattern_ops")
                .contains("(is_enabled, input_ratio ASC, output_ratio ASC, model_name ASC)")
                .contains("(is_enabled, output_ratio ASC, input_ratio ASC, model_name ASC)")
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("REFERENCES");
    }

    @Test
    void editingUsesOptimisticVersionAndNeverDeletesModelRows() throws IOException {
        String migration = read("sql/003_create_ai_model.sql");
        String modelMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml");
        String capabilityMapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelCapabilityMapper.xml");

        assertThat(migration)
                .contains("row_version BIGINT NOT NULL DEFAULT 1")
                .contains("CHECK (row_version > 0)");
        assertThat(modelMapper)
                .contains("<update id=\"updateEditable\">")
                .contains("row_version = row_version + 1")
                .contains("AND row_version = #{expectedVersion,jdbcType=BIGINT}")
                .contains("<update id=\"updateSearchTokensBatch\">")
                .contains("UPDATE ai_model AS model")
                .doesNotContain("DELETE FROM ai_model");
        assertThat(capabilityMapper)
                .contains("<delete id=\"deleteByAiModelId\">")
                .contains("DELETE FROM ai_model_capability")
                .contains("#{capability.id,jdbcType=BIGINT}");
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
