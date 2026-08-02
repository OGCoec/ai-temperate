package com.example.temperate.web.user.aiconversation.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证异步 Generation 的 SSE 写入异常会在正确的 Controller 范围内被当作连接断开处理，
 * 避免响应已经提交后再次尝试写入普通 JSON。
 */
final class AiConversationSseExceptionHandlerContractTest {

    @Test
    void generationControllerAndIoDisconnectsAreCoveredByTheSseAdvice()
            throws IOException {
        String source = Files.readString(
                findProjectRoot().resolve(
                        "ai-temperate-web/src/main/java/com/example/temperate/web/user/"
                                + "aiconversation/api/AiConversationExceptionHandler.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("AiConversationGenerationController.class")
                .contains("@ExceptionHandler(IOException.class)");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-web"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
