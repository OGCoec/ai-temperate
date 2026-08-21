package com.example.temperate.web.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该静态架构测试是来锁定模拟回调的 GET/POST 双协议、精确安全豁免、条件开关和 Java/前端完全分离边界。
 */
final class MembershipPaymentWebArchitectureTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void callbackSupportsGetAndPostButContainsNoFrontendOrMapperDependency()
            throws IOException {
        String controller = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/user/"
                        + "membership/payment/callback/"
                        + "SimulatedLiuhaoPaymentCallbackController.java");
        String receiveService = read(
                "ai-temperate-service/src/main/java/com/example/temperate/service/user/"
                        + "membership/payment/callback/impl/"
                        + "PaymentCallbackReceiveServiceImpl.java");

        assertThat(controller)
                .contains("@GetMapping", "@PostMapping")
                .contains("app.membership-payment.simulator")
                .doesNotContain("<html", "<style", "<script", "javascript:")
                .doesNotContain("MembershipOrderMapper", "MembershipPaymentCallbackMapper");
        assertThat(receiveService)
                .doesNotContain(".mapper.", "MembershipOrderMapper",
                        "MembershipPaymentCallbackMapper", "UserMembershipQuotaMapper");
    }

    @Test
    void securityOnlyExemptsExactPostCallbackPathFromCsrf() throws IOException {
        String security = read(
                "ai-temperate-web/src/main/java/com/example/temperate/web/auth/"
                        + "config/SecurityConfiguration.java");

        assertThat(security)
                .contains("SIMULATED_PAYMENT_CALLBACK_PATH")
                .contains("HttpMethod.GET")
                .contains("HttpMethod.POST")
                .contains("request.getContextPath() + SIMULATED_PAYMENT_CALLBACK_PATH")
                .doesNotContain("/internal/test/**");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
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
