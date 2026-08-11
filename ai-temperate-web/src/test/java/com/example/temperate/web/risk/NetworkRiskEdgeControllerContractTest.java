package com.example.temperate.web.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证网络风险边缘 Controller 只编排结构化响应和重定向，不在 Java 中嵌入前端页面。
 */
class NetworkRiskEdgeControllerContractTest {

    @Test
    void controllerKeepsH5PreAuthOutOfJsonAndUsesIndependentCompletionPage()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/risk/"
                        + "NetworkRiskEdgeController.java"));

        assertThat(source)
                .contains(
                        "AuthClientPlatform.fromHeader",
                        "PreAuthRiskBootstrapService",
                        "RISK_CHALLENGE_REQUIRED",
                        "RISK_BLOCKED",
                        "RISK_ASSESSMENT_UNAVAILABLE",
                        "outcome.issue().rawToken()",
                        "HttpStatus.SEE_OTHER",
                        "RISK_CONTEXT_UNAVAILABLE",
                        "RISK_CHALLENGE_UNAVAILABLE",
                        "DataAccessException",
                        "USER_COMPLETE_PATH",
                        "ADMIN_COMPLETE_PATH")
                .doesNotContain("if (!h5 || outcome.challenge() == null)")
                .doesNotContain("<html", "<script", "<style");
    }
}
