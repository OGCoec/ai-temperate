package com.example.temperate.service.risk.preauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 PreAuth v7 的信用快照、Challenge 与四态 WebRTC 门禁都保存在单个 Redis Hash 中。
 */
class PreAuthV7SingleHashContractTest {

    @Test
    void stateSchemaIsV7AndOldV6StateIsNotAccepted() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/risk/preauth/"
                        + "domain/PreAuthState.java"));

        assertThat(source)
                .contains("CURRENT_SCHEMA_VERSION = 7")
                .doesNotContain("CURRENT_SCHEMA_VERSION = 6");
    }

    @Test
    void storeUsesRequiredPhaseAndGenericRedisDeadline() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/risk/preauth/"
                        + "store/impl/RedisPreAuthStore.java"));

        assertThat(source).contains(
                "PreAuthState.CURRENT_SCHEMA_VERSION",
                "PreAuthWebRtcPhase.REQUIRED",
                "\"webRtcGeneration\"",
                "\"webRtcDeadlineAt\"",
                "PreAuthWebRtcBeginResult",
                "BEGIN_WEBRTC")
                .doesNotContain(
                        "\"webRtcPendingUntil\"",
                        "clock.instant().plus",
                        "\"webRtcStatus\"");
    }

    @Test
    void allPreAuthLuaScriptsReceiveSchemaVersionFromJava() throws Exception {
        Path scripts = Path.of("src/main/resources/lua/network-risk");
        try (var paths = Files.list(scripts)) {
            for (Path path : paths.filter(value -> value.getFileName().toString()
                    .startsWith("preauth_")).toList()) {
                String source = Files.readString(path);
                assertThat(source)
                        .as(path.getFileName().toString())
                        .doesNotContain(
                                "schemaVersion') ~= '4'",
                                "schemaVersion') ~= '5'",
                                "schemaVersion') ~= '6'",
                                "schemaVersion') ~= '7'");
            }
        }
    }

    @Test
    void newCodeDoesNotDefineIndependentTravelChallengeOrWebRtcStores()
            throws Exception {
        Path main = Path.of("src/main/java");
        String sources;
        try (var paths = Files.walk(main)) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        assertThat(sources).doesNotContain(
                "interface ImpossibleTravelEventStore",
                "class RedisImpossibleTravelEventStore",
                "interface RiskChallengeStore",
                "class RedisRiskChallengeStore",
                "interface WebRtcRiskStore",
                "class RedisWebRtcRiskStore");
    }
}
