package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证压缩候选模型的当前启用状态通过单条批量参数化 SQL 确认。
 */
final class AiModelAvailabilityMapperContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void enabledIdConfirmationUsesOneBoundedParameterizedQuery()
            throws IOException {
        String mapper = Files.readString(
                PROJECT_ROOT.resolve(
                        "ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml"),
                StandardCharsets.UTF_8);
        String query = mapper.substring(
                mapper.indexOf("<select id=\"findEnabledIds\""),
                mapper.indexOf(
                        "</select>",
                        mapper.indexOf("<select id=\"findEnabledIds\"")));

        assertThat(query)
                .contains("WHERE is_enabled = TRUE")
                .contains("id IN")
                .contains("<foreach collection=\"ids\"")
                .contains("#{id,jdbcType=BIGINT}")
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
