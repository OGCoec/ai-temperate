package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证异步图片 Worker 显式传递诊断上下文，并在预览派生与 Broker 发布边界保留双索引检查点。
 */
final class AiConversationImageStreamDiagnosticSourceContractTest {

    @Test
    void workerCarriesGenerationContextAndDeclaresPublishCheckpoints()
            throws IOException {
        String source = Files.readString(findProjectRoot().resolve(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "aiconversation/generation/worker/impl/"
                        + "AiConversationGenerationWorkerImpl.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("new AiConversationStreamingDiagnosticContext(")
                .contains("timingContext,")
                .contains("generationPublicId)))")
                .contains("P4_PREVIEW_PREPARATION_ATTEMPT")
                .contains("P5_PREVIEW_PUBLISH_RESULT")
                .contains("recordSafely(")
                .contains("preparePreviewSafely(")
                .contains("publishPreviewSafely(")
                .contains("isLatestPreview(")
                .contains("invalidatePreview(")
                .contains("publishResult.accepted()")
                .contains("publishResult.retained()")
                .contains("publishResult.observerCount()")
                .doesNotContain("details.put(\"base64\"")
                .doesNotContain("details.put(\"prompt\"")
                .doesNotContain("\"upstreamRequestId\", entry.getValue()");
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
