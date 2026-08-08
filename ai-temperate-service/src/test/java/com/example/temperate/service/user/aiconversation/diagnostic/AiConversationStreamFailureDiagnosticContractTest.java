package com.example.temperate.service.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 静态验证流失败诊断通过跨 Bean AOP 记录安全字段，且不会记录原始异常消息或响应内容。
 */
final class AiConversationStreamFailureDiagnosticContractTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void aspectLogsOnlyClassifiedMetadata() throws IOException {
        String aspect = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/diagnostic/AiConversationStreamFailureDiagnosticAspect.java");
        String implementation = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/diagnostic/impl/AiConversationStreamFailureDiagnosticServiceImpl.java");

        assertThat(aspect)
                .contains("@Around")
                .contains("event=ai_conversation_stream_failed")
                .contains("usagePublicId={}")
                .contains("reasonCode={}")
                .contains("rootCauseType={}")
                .contains("refundOutcome={}")
                .contains("upstreamErrorCode={}")
                .contains("upstreamErrorType={}")
                .contains("upstreamErrorParam={}")
                .contains("upstreamErrorMessage=\\\"{}\\\"")
                .contains("upstreamRequestId={}")
                .contains("upstreamContentType={}")
                .contains("upstreamBodySha256={}")
                .contains("upstreamCapturedBytes={}")
                .contains("upstreamBodyTruncated={}")
                .doesNotContain("getMessage()")
                .doesNotContain(", failure)");
        assertThat(aspect)
                .contains("STREAM_BACKPRESSURE_OVERFLOW");
        assertThat(implementation)
                .contains("@AiConversationStreamFailureDiagnostic")
                .contains("failureClassifier.classify(failure)");
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
