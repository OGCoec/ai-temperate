package com.example.temperate.web.admin.mailinspection.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证邮件检查诊断日志始终使用任务路由模板，包括尚未通过 PathVariable 校验的恶意路径段。
 */
final class MailInspectionRequestDiagnosticFilterTest {

    @Test
    void templatesValidAndInvalidJobSegments() {
        assertThat(MailInspectionRequestDiagnosticFilter.routeTemplate(
                "/api/admin/mail-inspection/jobs/"
                        + "AZ9nEjRWeJCrze8SNFZ4kA/events"))
                .isEqualTo(
                        "/api/admin/mail-inspection/jobs/{jobId}/events");
        assertThat(MailInspectionRequestDiagnosticFilter.routeTemplate(
                "/api/admin/mail-inspection/jobs/"
                        + "token-looking-but-invalid/events"))
                .isEqualTo(
                        "/api/admin/mail-inspection/jobs/{jobId}/events");
    }
}
