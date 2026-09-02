package com.example.temperate.web.auth.oauth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 H5 OAuth 只恢复原 WebRTC generation，并在登录完成后由后台 report 裁决会话。
 */
class OAuthWebRtcAsyncVerdictContractTest {

    @Test
    void controllerExposesResumeAndReadOnlyVerdictStatusWithoutAddingAnotherStart() throws Exception {
        String oauth = source("auth", "oauth", "controller", "OAuthController.java");
        String edge = source("risk", "webrtc", "WebRtcEdgeController.java");

        assertThat(oauth).contains(
                "@PostMapping(\"/webrtc/resume\")",
                "OAuthWebRtcAttemptService",
                "webRtcAttemptId",
                "webRtcGeneration");
        assertThat(edge).contains(
                "@PostMapping(\"/api/_edge/webrtc/verdict-status\")",
                "attemptId",
                "verificationService.report(");
        assertThat(oauth).doesNotContain("/webrtc/start");
    }

    @Test
    void h5CompletionUsesPendingTransportWhileLegacyAndAndroidRemainStrict() throws Exception {
        String transport = source("auth", "oauth", "transport", "OAuthLoginResultTransport.java");

        assertThat(transport).contains(
                "issuePendingOAuthVerdict(",
                "PendingSession pending",
                "verdictDeadlineAt",
                "cookieWriter.writeSession(");
        assertThat(transport).contains("promoteAuthenticatedAfterWebRtcVerified(");
        assertThat(transport.indexOf("issuePendingOAuthVerdict("))
                .isLessThan(transport.indexOf("cookieWriter.writeSession("));
    }

    @Test
    void interceptorAllowsOnlyAValidatedResumedAttemptToUsePendingCompletion() throws Exception {
        String interceptor = source("risk", "webrtc", "WebRtcVerificationInterceptor.java");

        assertThat(interceptor).contains(
                "OAuthWebRtcAttemptService",
                "isPendingH5OAuthCompletionAllowed(",
                "would_block");
        assertThat(interceptor).contains("/api/auth/oauth2/complete");
    }

    private static String source(String... parts) throws Exception {
        Path path = Path.of("src", "main", "java", "com", "example", "temperate", "web");
        for (String part : parts) {
            path = path.resolve(part);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
