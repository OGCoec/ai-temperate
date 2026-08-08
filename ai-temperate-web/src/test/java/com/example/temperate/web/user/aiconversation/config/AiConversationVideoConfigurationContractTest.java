package com.example.temperate.web.user.aiconversation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证视频 YAML 每个配置行都有紧邻中文注释，并且 Secret 没有生产默认值。
 */
final class AiConversationVideoConfigurationContractTest {

    @Test
    void everyVideoYamlLineHasAnAdjacentChineseComment() throws IOException {
        List<String> lines = Files.readAllLines(
                Path.of("src/main/resources/application.yml"),
                StandardCharsets.UTF_8);
        int start = lines.indexOf("    video-generation:");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = lines.indexOf("    attachments:");
        assertThat(end).isGreaterThan(start);
        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            assertThat(lines.get(index - 1).trim())
                    .as("视频配置第 %s 行必须有紧邻中文注释", index + 1)
                    .startsWith("#")
                    .matches(".*[\\u4e00-\\u9fff].*");
        }
        String section = String.join("\n", lines.subList(start, end));
        assertThat(section).contains(
                "hmac-secret: ${AI_CONVERSATION_VIDEO_FC_HMAC_SECRET:}");
    }
}
