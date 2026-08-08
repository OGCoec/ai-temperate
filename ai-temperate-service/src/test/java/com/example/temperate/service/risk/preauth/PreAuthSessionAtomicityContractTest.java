package com.example.temperate.service.risk.preauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证普通刷新会话、管理员会话和 PreAuth 的联合续期仍由单个 Lua 保证原子边界。
 */
class PreAuthSessionAtomicityContractTest {

    @Test
    void refreshSessionScriptsValidateAndRenewBoundPreAuthInOneExecution()
            throws Exception {
        for (String script : new String[] {
                "validate_access_session_with_preauth.lua",
                "validate_refresh_session_with_preauth.lua",
                "update_refresh_session_csrf_with_preauth.lua"
        }) {
            String source = Files.readString(Path.of(
                    "src/main/resources/lua/auth-session", script));
            assertThat(source)
                    .contains(
                            "KEYS[2]",
                            "expectedPreAuthSchemaVersion",
                            "preAuth[1] ~= expectedPreAuthSchemaVersion",
                            "'scope'",
                            "'authState'",
                            "'sessionType'",
                            "'sessionRefDigest'",
                            "'deviceDigest'");
            if (!script.startsWith("validate_access")) {
                assertThat(source).contains(
                        "local anonymousRecovery = promoteAnonymous",
                        "'authState', 'AUTHENTICATED'",
                        "redis.call('PEXPIREAT', KEYS[1]",
                        "redis.call('PEXPIREAT', KEYS[2]");
            }
        }
    }

    @Test
    void eventAndChallengeStateUseOnlyThePreAuthV6Hash()
            throws Exception {
        String event = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/"
                        + "preauth_record_travel_event.lua"));
        String activate = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/"
                        + "preauth_activate_challenge.lua"));
        String consume = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/"
                        + "preauth_consume_challenge.lua"));

        assertThat(event)
                .contains(
                        "'impossibleTravelEvents'",
                        "'impossibleTravelCount'",
                        "cjson.encode(compact)",
                        "maximumEvents");
        assertThat(activate)
                .contains(
                        "'activeChallengeNonce'",
                        "'activeChallengeContextDigest'",
                        "'challengeIssuedCount'",
                        "return {0, activeNonce, activeExpiresAt}");
        assertThat(consume)
                .contains(
                        "'activeChallengeNonce'",
                        "'lastTrustedIpDigest'",
                        "'challengePassedCount'",
                        "'challengeVerifiedUntil'")
                .doesNotContain("KEYS[2]", "UNLINK");
    }

    @Test
    void loginRotationCopiesRiskSnapshotAndRebindsDecisionContext()
            throws Exception {
        String rotate = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/"
                        + "preauth_rotate_authenticated.lua"));

        assertThat(rotate)
                .contains(
                        "redis.call('HGETALL', KEYS[1])",
                        "'lastDecisionContextDigest', ARGV[7]",
                        "'schemaVersion', ARGV[1]",
                        "'activeChallengeNonce', ''",
                        "'activeChallengeIpDigest', ''",
                        "'activeChallengeContextDigest', ''",
                        "'activeChallengeExpiresAt', ''",
                        "sourcePhase ~= ARGV[8]",
                        "sourceGeneration ~= expectedSourceGeneration",
                        "'webRtcPhase', ARGV[10]",
                        "'webRtcGeneration', ARGV[11]",
                        "'webRtcDeadlineAt'",
                        "'webRtcIps', ARGV[12]",
                        "sourcePhase ~= 'VERIFIED'",
                        "generation ~= sourceGeneration + 1",
                        "redis.call('UNLINK', KEYS[1])")
                .doesNotContain(
                        "'impossibleTravelCount', '0'",
                        "'challengeIssuedCount', '0'",
                        "'challengePassedCount', '0'");
    }

    @Test
    void administratorStoreUsesOneScriptForBothSlidingTtls()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/admin/session/impl/"
                        + "RedisAdminSessionStore.java"));

        assertThat(source)
                .contains(
                        "TOUCH_WITH_PREAUTH_SCRIPT",
                        "keyFactory.adminSessionTokensKey()",
                        "keyFactory.adminPreAuthKey(binding.tokenDigest())",
                        "local anonymousRecovery = ARGV[10] == '1'",
                        "preauth[1] ~= ARGV[11]",
                        "PreAuthState.CURRENT_SCHEMA_VERSION",
                        "'authState', 'AUTHENTICATED'",
                        "redis.call('HEXPIRE', KEYS[1]",
                        "redis.call('PEXPIRE', KEYS[2]");
    }
}
