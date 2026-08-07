package com.example.temperate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定图片运行时故障所需的 Actuator 就绪探针配置与匿名安全边界。
 */
final class AiConversationReadinessHealthTest {

    @Test
    void exposesOnlyDetailFreeHealthProbesAndKeepsLegacyHealthEndpoint() throws Exception {
        Path root = findProjectRoot();
        String yaml = Files.readString(root.resolve(
                "ai-temperate-web/src/main/resources/application.yml"),
                StandardCharsets.UTF_8);
        String security = Files.readString(root.resolve(
                "ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/"
                        + "SecurityConfiguration.java"),
                StandardCharsets.UTF_8);

        assertThat(yaml)
                .contains("show-details: never")
                .contains("show-components: never")
                .contains("enabled: true")
                .contains("include: health");
        assertThat(security)
                .contains("\"/api/health\"")
                .contains("\"/actuator/health/liveness\"")
                .contains("\"/actuator/health/readiness\"");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-web"))
                    && Files.isDirectory(current.resolve("ai-temperate-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
