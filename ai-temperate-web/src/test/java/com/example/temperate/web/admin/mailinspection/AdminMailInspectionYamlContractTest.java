package com.example.temperate.web.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证邮箱检查 YAML 区块的每一个配置行前都有紧邻中文说明。
 */
final class AdminMailInspectionYamlContractTest {

    @Test
    void everyMailInspectionConfigurationLineHasAdjacentComment()
            throws IOException {
        Path yaml = Path.of("src/main/resources/application.yml");
        List<String> lines = Files.readAllLines(yaml, StandardCharsets.UTF_8);
        int start = indexOf(lines, "    mail-inspection:");
        int end = start + 1;
        while (end < lines.size()
                && (lines.get(end).isBlank()
                        || lines.get(end).startsWith("    ")
                        || lines.get(end).trim().startsWith("#"))) {
            end++;
        }

        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            assertThat(index)
                    .as("configuration line %s must have a preceding comment", index + 1)
                    .isGreaterThan(0);
            assertThat(lines.get(index - 1).trim())
                    .as("configuration line %s must have a preceding comment", index + 1)
                    .startsWith("#");
        }

        String mailInspectionYaml = String.join("\n", lines.subList(start, end));
        assertThat(mailInspectionYaml)
                .doesNotContain("max-batch-size", "recovery-max-messages");
    }

    private static int indexOf(List<String> lines, String expected) {
        int index = lines.indexOf(expected);
        assertThat(index).isGreaterThanOrEqualTo(0);
        return index;
    }
}
