package com.example.temperate.service.auth.oauth.webrtc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 验证 OAuth WebRTC attempt、待裁决会话和 report 最终裁决共享 Redis 原子边界。
 */
class OAuthWebRtcAsyncVerdictLuaContractTest {

    private static final Path OAUTH_WEBRTC =
            Path.of("src/main/resources/lua/auth-oauth-webrtc");
    private static final Path SESSION =
            Path.of("src/main/resources/lua/auth-session");

    @Test
    void suspensionPreservesTheOriginalPreAuthAndGeneration() throws Exception {
        String script = script(OAUTH_WEBRTC.resolve("suspend_attempt.lua"));

        assertThat(script).contains(
                "oauth_suspended",
                "webrtcgeneration",
                "webrtcdeadlineat",
                "preauthtokendigest");
        assertThat(script).doesNotContain("incr", "newgeneration", "unlink', keys[1]");
    }

    @Test
    void pendingSessionIssuanceRotatesPreAuthWithoutAdvancingGeneration() throws Exception {
        String script = script(OAUTH_WEBRTC.resolve("issue_pending_session.lua"));

        assertThat(script).contains(
                "resumed",
                "riskverdict",
                "pending",
                "riskverdictdeadlineat",
                "authenticated",
                "sessiontype");
        assertThat(script).doesNotContain("webrtcgeneration', tostring(tonumber");
        assertThat(script).contains(
                "local deadline = tonumber",
                "'verdictdeadlineat'");
        assertThat(script).contains(
                "'webrtcowner', 'oauth'",
                "'oauthwebrtcattemptdigest'");
    }

    @Test
    void repeatedResumeCannotExtendTheExistingVerdictDeadline() throws Exception {
        String script = script(OAUTH_WEBRTC.resolve("resume_attempt.lua"));

        assertThat(script).contains(
                "attemptstatus == 'resumed'",
                "samegeneration",
                "return {1, currentgeneration, fallbackused}",
                "verdictdeadline + 60000");
    }

    @Test
    void reportAtomicallyActivatesOrRevokesTheBoundRefreshSession() throws Exception {
        String script = script(OAUTH_WEBRTC.resolve("decide_report.lua"));

        assertThat(script).contains(
                "verified",
                "failed",
                "active",
                "hdel",
                "unlink",
                "riskverdictattemptid");
        assertThat(script).contains(
                "phase == 'verified' and attemptstatus == 'resumed'",
                "webrtcowner",
                "oauthwebrtcattemptdigest");
        assertThat(script).contains(
                "riskverdict') ~= 'pending'",
                "riskverdictgeneration");
    }

    @Test
    void everyRefreshValidationPathRejectsAnExpiredPendingVerdict() throws Exception {
        for (String name : new String[] {
                "validate_access_session.lua",
                "validate_access_session_with_preauth.lua",
                "validate_refresh_session.lua",
                "validate_refresh_session_with_preauth.lua",
                "update_refresh_session_csrf.lua",
                "update_refresh_session_csrf_with_preauth.lua",
                "validate_session_binding.lua"
        }) {
            String script = script(SESSION.resolve(name));
            assertThat(script).contains(
                    "riskverdict",
                    "riskverdictdeadlineat",
                    "return {7}");
        }
    }

    private static String script(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .replace("_", "");
    }
}
