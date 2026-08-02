package com.example.temperate.service.user.aiconversation.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证普通 OpenAI Starter 的流式边界、请求参数、Usage 解析和禁用重试约束。
 */
final class SpringAiCliProxyConversationModelClientContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void clientUsesOneStreamingRequestAndFinalUsage() throws IOException {
        String client = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/impl/SpringAiCliProxyConversationModelClient.java");
        String yaml = read(
                "ai-temperate-web/src/main/resources/application.yml");
        String testYaml = read(
                "ai-temperate-web/src/test/resources/application-test.yml");
        String pom = read("ai-temperate-service/pom.xml");

        assertThat(client)
                .contains("import org.springframework.ai.openai.OpenAiChatModel;")
                .contains("import org.springframework.ai.openai.OpenAiChatOptions;")
                .contains(".maxCompletionTokens(maxCompletionTokens)")
                .contains(".reasoningEffort(request.reasoningEffort().upstreamValue())")
                .contains(".N(1)")
                .contains(".store(false)")
                .contains(".streamUsage(true)")
                .contains(".stream()")
                .contains(".chatResponse()")
                .contains("OpenAiApi.Usage")
                .contains("promptTokensDetails()")
                .contains("completionTokenDetails()")
                .contains("private Media modelInputMedia(")
                .contains("URI.create(modelUrl)")
                .contains("modelUrl);")
                .contains("AI_ATTACHMENT_CAPABILITY_UNSUPPORTED")
                .doesNotContain("private static boolean modelMediaCategory(")
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
                .doesNotContain("retryWhen(")
                .doesNotContain("OpenAiSdk")
                .doesNotContain("URI.create(attachmentService.resolveModelUrl")
                .doesNotContain("completionTokensDetails");
        int compactStart = client.indexOf("public String compact(");
        int requiredModelStart = client.indexOf(
                "private OpenAiChatModel requiredChatModel()", compactStart);
        assertThat(compactStart).isGreaterThanOrEqualTo(0);
        assertThat(requiredModelStart).isGreaterThan(compactStart);
        assertThat(client.substring(compactStart, requiredModelStart))
                .doesNotContain("reasoningEffort");
        assertThat(pom)
                .contains("<artifactId>spring-ai-starter-model-openai</artifactId>")
                .doesNotContain("spring-ai-starter-model-openai-sdk");
        assertThat(yaml)
                .contains("max-attempts: 1")
                .contains("chat: ${AI_INFERENCE_SPRING_CHAT_MODEL:none}")
                .contains("embedding: none")
                .contains("image: none")
                .contains("speech: none")
                .contains("transcription: none")
                .contains("moderation: none")
                .contains("base-url: ${AI_INFERENCE_CLI_PROXY_BASE_URL:http://127.0.0.1:8317}")
                .contains("completions-path: /v1/chat/completions")
                .doesNotContain("openai-sdk:")
                .doesNotContain("max-retries:")
                .doesNotContain("AI_INFERENCE_CLI_PROXY_BASE_URL:http://127.0.0.1:8317/v1")
                .doesNotContain("AI_INFERENCE_FIRST_BYTE_TIMEOUT")
                .doesNotContain("AI_INFERENCE_CONNECT_TIMEOUT");
        assertThat(testYaml)
                .contains("chat: none")
                .contains("embedding: none")
                .contains("image: none")
                .contains("speech: none")
                .contains("transcription: none")
                .contains("moderation: none")
                .contains("openai:")
                .doesNotContain("openai-sdk:");
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
