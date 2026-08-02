package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 RabbitMQ 延迟插件测试镜像固定官方版本和摘要，避免隔离测试静默使用未知镜像。
 */
final class RabbitAiConversationGenerationTestImageContractTest {

    private static final String PLUGIN_SHA256 =
            "567f876378e70af9d949de4066bbb2fc30162b46bcbc9efe2430076b172e4a87";

    @Test
    void delayedImagePinsRabbitAndPluginArtifact() throws IOException {
        String dockerfile = Files.readString(
                findProjectRoot().resolve("docker/test/rabbitmq-delayed/Dockerfile"),
                StandardCharsets.UTF_8);

        assertThat(dockerfile)
                .contains("FROM rabbitmq:4.1-management")
                .contains("rabbitmq_delayed_message_exchange-4.1.0.ez")
                .contains("sha256:" + PLUGIN_SHA256)
                .contains("chmod 0644 /plugins/rabbitmq_delayed_message_exchange-4.1.0.ez")
                .contains("rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isDirectory(current.resolve("sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
