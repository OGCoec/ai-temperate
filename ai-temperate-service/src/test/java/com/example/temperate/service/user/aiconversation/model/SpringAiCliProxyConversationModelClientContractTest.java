package com.example.temperate.service.user.aiconversation.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证 Spring AI 边界传递推理强度、最大输出、最终 Usage 且不声明自动重试。
 */
final class SpringAiCliProxyConversationModelClientContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void clientUsesOneStreamingRequestAndFinalUsage() throws IOException {
        String client = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/impl/SpringAiCliProxyConversationModelClient.java");
        String yaml = read(
                "ai-temperate-web/src/main/resources/application.yml");

        assertThat(client)
                .contains(".maxCompletionTokens(maxCompletionTokens)")
                .contains(".reasoningEffort(request.reasoningEffort().upstreamValue())")
                .contains(".streamUsage(true)")
                .contains("AiConversationAttachmentState.AVAILABLE")
                .contains("output.getMedia()")
                .contains("attachmentProperties.maxFilesPerMessage()")
                .contains("attachmentProperties.maxTotalBytesPerMessage()")
                .contains("GeneratedMediaBatch")
                .contains("generatedMedia.truncated()")
                .contains(".takeUntilOther(totalDeadline(")
                .contains("properties.maxStreamDuration()")
                .contains("failureClassifier.classify(failure)")
                .contains("new AiConversationException(")
                .contains("failure)")
                .doesNotContain("firstByteTimeout")
                .doesNotContain(".take(properties.maxStreamDuration())")
                .doesNotContain("retry(")
                .doesNotContain("retryWhen(");
        int compactStart = client.indexOf("public String compact(");
        int requiredModelStart = client.indexOf(
                "private OpenAiSdkChatModel requiredChatModel()", compactStart);
        assertThat(client.substring(compactStart, requiredModelStart))
                .doesNotContain("reasoningEffort");
        assertThat(yaml)
                .contains("max-retries: 0")
                .contains("chat: ${AI_INFERENCE_SPRING_CHAT_MODEL:none}")
                .doesNotContain("AI_INFERENCE_FIRST_BYTE_TIMEOUT")
                .doesNotContain("AI_INFERENCE_CONNECT_TIMEOUT");
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
