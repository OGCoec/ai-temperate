package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证模型图标 Mapper 使用有界分页、行锁和参数化逻辑关联语句。
 */
final class AiModelIconMapperContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void mapperSupportsCrudLocksAndReferenceChecks() throws IOException {
        String mapper = Files.readString(
                PROJECT_ROOT.resolve(
                        "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelIconMapper.xml"),
                StandardCharsets.UTF_8);

        assertThat(mapper)
                .contains("<insert id=\"insert\"")
                .contains("#{id,jdbcType=BIGINT}")
                .doesNotContain("useGeneratedKeys")
                .contains("<select id=\"findPage\"")
                .contains("ORDER BY LOWER(icon_name), id")
                .contains("<select id=\"findByIdForShare\"")
                .contains("FOR SHARE")
                .contains("<select id=\"findByIdForUpdate\"")
                .contains("FOR UPDATE")
                .contains("<select id=\"countModelReferences\"")
                .contains("WHERE icon_id = #{iconId,jdbcType=BIGINT}")
                .contains("<select id=\"existsEnabledReference\"")
                .contains("AND is_enabled = TRUE")
                .contains("<delete id=\"deleteById\"")
                .doesNotContain("${");
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
