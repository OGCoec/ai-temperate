package com.example.temperate.service.risk.preauth.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 PreAuth v7 WebRTC phase、generation、证据保留和截止时间全部通过专用 Lua 原子维护。
 */
class RedisPreAuthStoreWebRtcTest {

    @Test
    void assessmentScriptStartsANewRequiredGenerationWhenIpChanges()
            throws Exception {
        String script = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_record_assessment.lua"));

        assertThat(script)
                .contains(
                        "currentIpDigest",
                        "webRtcPhase', 'REQUIRED'",
                        "webRtcGeneration",
                        "webRtcDeadlineAt",
                        "webRtcFailureReason",
                        "webRtcIps")
                .doesNotContain("webRtcStatus");
    }

    @Test
    void beginScriptUsesRedisTimeAndNeverExtendsAnExistingPendingWindow()
            throws Exception {
        String script = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_begin_webrtc.lua"));

        assertThat(script)
                .contains(
                        "redis.call('TIME')",
                        "currentPhase ~= 'REQUIRED'",
                        "currentPhase == 'PENDING'",
                        "webRtcDeadlineAt",
                        "PENDING",
                        "START_TIMEOUT",
                        "REPORT_TIMEOUT")
                .doesNotContain("webRtcPendingUntil");
        assertThat(script.indexOf("currentPhase == 'PENDING'"))
                .isLessThan(script.lastIndexOf("webRtcDeadlineAt"));
    }

    @Test
    void reportScriptChecksGenerationDeadlineAndAllBindings() throws Exception {
        String script = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_write_webrtc.lua"));

        assertThat(script)
                .contains(
                        "schemaVersion",
                        "deviceDigest",
                        "scope",
                        "currentIpDigest",
                        "webRtcGeneration",
                        "webRtcDeadlineAt",
                        "REPORT_TIMEOUT",
                        "IP_FAMILY_INCOMPLETE",
                        "retainsEvidence",
                        "redis.call('TIME')",
                        "return -2",
                        "return 4",
                        "webRtcOwner",
                        "return 5")
                .contains("HSET", "PEXPIRE")
                .doesNotContain("webRtcStatus");
        assertThat(script.indexOf("if currentPhase == 'FAILED'"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(script.indexOf("if ARGV[6] == 'VERIFIED'"));
    }

    @Test
    void timeoutScriptExpiresRequiredAndPendingWithDifferentReasons() throws Exception {
        String script = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_expire_webrtc.lua"));

        assertThat(script)
                .contains(
                        "currentPhase ~= 'REQUIRED' and currentPhase ~= 'PENDING'",
                        "webRtcGeneration",
                        "webRtcDeadlineAt",
                        "START_TIMEOUT",
                        "REPORT_TIMEOUT",
                        "redis.call('TIME')")
                .contains("HDEL", "webRtcIps");
    }

    @Test
    void rotationPreservesVerifiedOrCreatesANewRequiredGeneration()
            throws Exception {
        String rotate = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_rotate_authenticated.lua"));

        assertThat(rotate)
                .contains(
                        "HGETALL",
                        "sourcePhase ~= ARGV[8]",
                        "sourceGeneration ~= expectedSourceGeneration",
                        "webRtcPhase', ARGV[10]",
                        "webRtcGeneration', ARGV[11]",
                        "webRtcDeadlineAt",
                        "webRtcIps', ARGV[12]",
                        "redis.call('TIME')")
                .doesNotContain("webRtcStatus");
    }

    @Test
    void v7HashRequiresExplicitPhaseGenerationAndGenericDeadline() throws Exception {
        String store = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/risk/preauth/"
                        + "store/impl/RedisPreAuthStore.java"));

        assertThat(store).contains(
                "PreAuthWebRtcPhase.valueOf(value(values, \"webRtcPhase\"))",
                "Long.parseLong(value(values, \"webRtcGeneration\"))",
                "nullableEpochMillis(values, \"webRtcDeadlineAt\")");
    }
}
