package com.example.temperate.web.admin.mailinspection.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证邮件检查 Rabbit 监听控制器输出稳定故障阶段，同时禁止泄露异常文本或受保护凭证。
 */
final class RabbitMailInspectionListenerLoggingContractTest {

    @Test
    void listenerLogsStableFailurePointAndRootCauseType() throws Exception {
        String source = Files.readString(sourcePath());

        assertThat(source).contains(
                "admin_mail_inspection_listener_failed",
                "LISTENER_PREPARE",
                "LISTENER_START",
                "LISTENER_LOOKUP",
                "LISTENER_STOP",
                "LISTENER_SET_CONCURRENCY",
                "failureOrigin={}",
                "rootCauseType={}");
        assertThat(source).doesNotContain(
                "getConcurrentConsumers()",
                "Unresolved compilation problem",
                "exception.getMessage()",
                "exception.getLocalizedMessage()",
                "protectedPayload",
                "refreshToken");
    }

    private static Path sourcePath() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(
                    "ai-temperate-web/src/main/java/com/example/temperate/web/"
                            + "admin/mailinspection/config/"
                            + "RabbitMailInspectionListenerControl.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate listener control source");
    }
}
