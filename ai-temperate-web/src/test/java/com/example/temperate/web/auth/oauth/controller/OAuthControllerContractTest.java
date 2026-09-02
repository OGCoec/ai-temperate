package com.example.temperate.web.auth.oauth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 OAuth 控制器只暴露批准的固定端点，并禁止客户端输入任意返回地址。
 */
class OAuthControllerContractTest {

    @Test
    void shouldExposeExactApprovedRoutesWithoutClientReturnUrl() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "example", "temperate", "web",
                "auth", "oauth", "controller", "OAuthController.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains(
                "@PostMapping(\"/start\")",
                "@GetMapping(\"/authorization/{provider}\")",
                "@GetMapping(\"/code/{provider}\")",
                "@PostMapping(\"/google/native/complete\")",
                "@GetMapping(\"/flow/status\")",
                "@PostMapping(\"/phone/start\")",
                "@PostMapping(\"/phone/turnstile\")",
                "@PostMapping(\"/phone/send\")",
                "@PostMapping(\"/phone/verify\")",
                "@PostMapping(\"/complete\")",
                "@PostMapping(\"/cancel\")");
        assertThat(source).doesNotContain("returnUrl");
    }

    @Test
    void callbackUsesSafeFailureDiagnosticsAndKeepsDatabaseFinalizationOutOfCallback()
            throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "example", "temperate", "web",
                "auth", "oauth", "controller", "OAuthController.java"),
                StandardCharsets.UTF_8);

        assertThat(source).contains(
                "callbackFailureLogger.logAuthorizationRejected(",
                "callbackFailureLogger.logFailure(",
                "providerCompletionService.accept(");
        assertThat(source).doesNotContain(
                "finalizeIdentity(",
                "bindGithubSubjectIfAbsent(",
                "bindGoogleSubjectIfAbsent(");
    }

    @Test
    void androidGoogleCannotBeDowngradedToBrowserOAuth() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "example", "temperate", "web",
                "auth", "oauth", "controller", "OAuthController.java"),
                StandardCharsets.UTF_8);

        assertThat(source).contains(
                "if (platform == OAuthClientPlatform.ANDROID",
                "&& request.provider() == OAuthProvider.GOOGLE",
                "return OAuthInteractionMode.GOOGLE_NATIVE;");
        assertThat(source).doesNotContain(
                "request.interactionMode() != OAuthInteractionMode.BROWSER");
    }

    @Test
    void h5OAuthWritesSessionOnlyAfterStrictVerifiedPreAuthPromotion()
            throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "example", "temperate", "web",
                "auth", "oauth", "transport", "OAuthLoginResultTransport.java"),
                StandardCharsets.UTF_8);

        int promotion = source.indexOf("PreAuthIssue preAuth = promotePreAuth(");
        int sessionCookie = source.indexOf("cookieWriter.writeSession(");
        assertThat(promotion).isGreaterThanOrEqualTo(0);
        assertThat(sessionCookie).isGreaterThan(promotion);
        assertThat(source).contains(
                "promoteAuthenticatedAfterWebRtcVerified(",
                "platform == AuthClientPlatform.H5",
                "networkRiskProperties.mode() == NetworkRiskMode.ENFORCE",
                "SessionAuthenticationErrorCode.PREAUTH_REQUIRED");
    }

    @Test
    void completionEndpointDelegatesExactlyOnceAndDoesNotRetrySessionIssuance()
            throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "example", "temperate", "web",
                "auth", "oauth", "controller", "OAuthController.java"),
                StandardCharsets.UTF_8);
        String invocation = "loginCompletionService.complete(access)";

        assertThat(source.indexOf(invocation)).isGreaterThanOrEqualTo(0);
        assertThat(source.indexOf(invocation, source.indexOf(invocation) + 1)).isEqualTo(-1);
        assertThat(source).contains("loginResultTransport.write(");
    }
}
