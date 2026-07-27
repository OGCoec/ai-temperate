package com.example.temperate.service.risk.preauth.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 PreAuth WebRTC 两字段由专用 Lua 原子维护，且 IP 评估变化会清除旧网络结果。
 */
class RedisPreAuthStoreWebRtcTest {

    @Test
    void assessmentScriptClearsExactlyTheTwoWebRtcFieldsWhenIpChanges()
            throws Exception {
        String script = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_record_assessment.lua"));

        assertThat(script)
                .contains("currentIpDigest", "webRtcStatus", "webRtcIps", "HDEL")
                .doesNotContain("webRtcSeenAt", "webRtcMismatchCount", "webRtcRiskLevel");
    }

    @Test
    void webRtcScriptChecksAllBindingsAndWritesBothFieldsTogether()
            throws Exception {
        String script = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_write_webrtc.lua"));

        assertThat(script)
                .contains(
                        "schemaVersion",
                        "deviceDigest",
                        "scope",
                        "currentIpDigest",
                        "webRtcStatus",
                        "webRtcIps",
                        "PEXPIRE")
                .contains("HSET")
                .contains(
                        "previousStatus == 'true'",
                        "ARGV[4] == 'false'",
                        "ARGV[6] == '0'",
                        "return 2",
                        "return 3")
                .doesNotContain("webRtcSeenAt", "webRtcMismatchCount", "webRtcRiskLevel");
    }

    @Test
    void creationOmitsWebRtcFieldsAndRotationOverwritesReencryptedState()
            throws Exception {
        String create = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_create.lua"));
        String rotate = Files.readString(Path.of(
                "src/main/resources/lua/network-risk/preauth_rotate_authenticated.lua"));

        assertThat(create).doesNotContain("webRtcStatus", "webRtcIps");
        assertThat(rotate)
                .contains(
                        "HGETALL",
                        "'webRtcStatus', ARGV[7]",
                        "'webRtcIps', ARGV[8]")
                .contains("HDEL", "webRtcStatus", "webRtcIps");
    }

    @Test
    void v4HashReadsMissingOptionalWebRtcFieldsAsNull() throws Exception {
        String store = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/risk/preauth/"
                        + "store/impl/RedisPreAuthStore.java"));

        assertThat(store).contains(
                "nullableBoolean(values, \"webRtcStatus\")",
                "nullable(values, \"webRtcIps\")");
    }
}
