package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证语音配置中文注释、Controller 文档元数据和前后端源码分离约束。
 */
final class VoiceConfigurationContractTest {

    @Test
    void everyVoiceYamlLineHasAnAdjacentChineseComment() throws IOException {
        List<String> lines = Files.readAllLines(
                Path.of("src/main/resources/application.yml"),
                StandardCharsets.UTF_8);
        int start = lines.indexOf("  voice:");
        assertThat(start).isGreaterThanOrEqualTo(0);
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
            assertThat(lines.get(index - 1).trim())
                    .as("语音配置第 %s 行必须有紧邻中文注释", index + 1)
                    .startsWith("#")
                    .matches(".*[\\u4e00-\\u9fff].*");
        }
    }

    @Test
    void controllerKeepsOpenApiMetadataAndContainsNoFrontendSource() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/user/voice/VoiceSessionTicketController.java"));

        assertThat(source).contains("@Tag(", "@Operation(", "CacheControl.noStore()");
        assertThat(source).doesNotContain("<html", "<style", "<script", "javascript:");
    }
}
