package com.example.temperate.service.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证四个时序边界已接入且诊断实现不记录模型正文或建立内部订阅。
 */
final class AiConversationStreamTimingContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void modelAndResponseFlowExposeAllTimingBoundaries() throws IOException {
        String modelClient = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/impl/SpringAiCliProxyConversationModelClient.java");
        String responseService = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java");
        String worker = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "aiconversation/generation/worker/impl/"
                        + "AiConversationGenerationWorkerImpl.java");
        String observer = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "aiconversation/generation/observer/impl/"
                        + "AiConversationGenerationObserverServiceImpl.java");

        assertThat(modelClient)
                .contains("@AiConversationStreamTiming")
                .contains("AiConversationStreamTimingBoundary.SPRING_AI_RAW")
                .containsPattern("AiConversationStreamTimingBoundary\\s*\\.\\s*"
                        + "AFTER_BOUNDED_ELASTIC");
        assertThat(responseService)
                .contains("AiConversationStreamTimingBoundary.AFTER_STREAM_BATCHER")
                .contains("AiConversationStreamTimingBoundary.SSE_EVENT_READY")
                .contains("timingDiagnosticService.withSession(")
                .contains("AiConversationStreamTimingPath.DIRECT_RESPONSE");
        assertThat(worker)
                .contains("AiConversationStreamTimingBoundary.AFTER_STREAM_BATCHER")
                .contains("timingDiagnosticService.withSession(")
                .contains("AiConversationStreamTimingPath.ASYNC_GENERATION_WORKER");
        assertThat(observer)
                .contains("AiConversationStreamTimingBoundary.SSE_EVENT_READY")
                .contains("timingDiagnosticService.withSession(")
                .contains("AiConversationStreamTimingPath.ASYNC_GENERATION_OBSERVER");
    }

    @Test
    void diagnosticsNeverSubscribeOrLogUntrustedPayloads() throws IOException {
        String implementation = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/diagnostic/impl/AiConversationStreamTimingDiagnosticServiceImpl.java");

        assertThat(implementation)
                .doesNotContain(".subscribe(")
                .doesNotContain("getMessage()")
                .doesNotContain("prompt")
                .doesNotContain("modelText")
                .doesNotContain("cookie")
                .doesNotContain("apiKey");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
