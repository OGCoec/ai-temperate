package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 SSE Observer 源码边界不会重新耦合上游订阅、退款或资金终态。
 */
class AiConversationGenerationObserverSourceContractTest {

    @Test
    void observerOnlyDetachesAndUsesShortTransportHeartbeat() throws IOException {
        String source = Files.readString(findProjectRoot().resolve(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "aiconversation/generation/observer/impl/"
                        + "AiConversationGenerationObserverServiceImpl.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("observerStateService.detach")
                .contains("asyncProperties.observerHeartbeat()")
                .contains("AiConversationStreamTimingBoundary.SSE_EVENT_READY")
                .contains("AiConversationStreamTimingPath.ASYNC_GENERATION_OBSERVER")
                .contains("timingDiagnosticService.withSession(")
                .contains("P6_OBSERVER_RECEIVED")
                .contains("P7_SSE_READY")
                .contains("recordSafely(")
                .contains("releasePreviewSafely(")
                .doesNotContain("refundFailed(")
                .doesNotContain("terminalService.freeze(")
                .doesNotContain("modelClient.stream(");
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
