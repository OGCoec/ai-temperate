package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 Generation 创建事务保留初始失联时间，并由幂等重放补偿提交后调度发布空窗。
 */
final class AiConversationGenerationCreationSourceContractTest {

    @Test
    void initialGenerationIsRecoverableBeforeFirstObserverAndReplayRepublishesQueuedWork()
            throws IOException {
        String source = Files.readString(findProjectRoot().resolve(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "aiconversation/generation/impl/"
                        + "AiConversationGenerationCreationTransactionServiceImpl.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("generation.setObserverStatus("
                        + "AiConversationGenerationObserverStatus.DETACHED.code())")
                .contains("generation.setDetachedAt(now)")
                .contains("existing.getGenerationStatus()")
                .contains("AiConversationGenerationStatus.QUEUED.code()")
                .contains("new AiConversationGenerationDispatchEvent(")
                .contains("new AiConversationGenerationDetachedEvent(");
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
