package com.example.temperate.service.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证邮箱检查恢复、任务接收和监听容器边界提供可定位且不泄露第三方原始文本的日志契约。
 */
final class MailInspectionDiagnosticLoggingContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void recoveryLogsStableFailurePointAndRootCauseType() throws Exception {
        String source = source(
                "ai-temperate-service/src/main/java/com/example/temperate/service/"
                        + "admin/mailinspection/recovery/impl/"
                        + "MailInspectionRecoveryCoordinatorImpl.java");

        assertThat(source).contains(
                "admin_mail_inspection_recovery_started",
                "admin_mail_inspection_recovery_queue_failed",
                "admin_mail_inspection_recovery_ready",
                "failurePoint={}",
                "rootCauseType={}",
                "RECOVERY_BASIC_GET",
                "RECOVERY_PAYLOAD_DECRYPT");
        assertThat(source).doesNotContain(
                "exception.getMessage()",
                "exception.getLocalizedMessage()");
    }

    @Test
    void acceptanceLogsExposeOnlyStableInfrastructureMetadata()
            throws Exception {
        String store = source(
                "ai-temperate-service/src/main/java/com/example/temperate/service/"
                        + "admin/mailinspection/job/"
                        + "InMemoryAdminMailInspectionJobStore.java");

        assertThat(store).contains(
                "admin_mail_inspection_acceptance_initialized",
                "admin_mail_inspection_create_rejected",
                "admin_mail_inspection_acceptance_changed");
        assertThat(store).doesNotContain(
                "exception.getMessage()",
                "exception.getLocalizedMessage()",
                "protectedPayload",
                "refreshToken");
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(PROJECT_ROOT.resolve(relativePath));
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isDirectory(current.resolve("ai-temperate-web"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
