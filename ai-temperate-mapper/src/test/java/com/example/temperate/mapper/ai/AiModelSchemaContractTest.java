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
    void capabilityTableUsesApplicationIdWhitelistAndLogicalAssociation() throws IOException {
        String sql = read("sql/004_create_ai_model_capability.sql");
        String orphanCheck = read("sql/checks/ai_model_capability_orphans.sql");

        assertThat(sql)
                .contains("id BIGINT NOT NULL")
                .doesNotContain("id BIGINT GENERATED ALWAYS AS IDENTITY")
                .contains("UNIQUE (ai_model_id, capability_code)")
                .contains("CHAT_COMPLETIONS")
                .contains("RESPONSES")
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
    void adminFiltersUseLiteralPrefixSearchAndDedicatedIndexes() throws IOException {
        String migration = read("sql/003_create_ai_model.sql");
        String mapper = read(
                "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml");

        assertThat(mapper)
                .contains("LOWER(model.model_name) LIKE")
                .contains("LOWER(model.vendor) LIKE")
                .contains("ESCAPE '\\'")
                .contains("is_enabled = #{enabled,jdbcType=BOOLEAN}")
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
