package com.example.temperate.service.risk.preauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 PreAuth v4 的信用快照、事件、计数和活动 Challenge 均归并到一个 Redis Hash。
 */
class PreAuthV4SingleHashContractTest {

    @Test
    void storeWritesCompleteSnapshotWithoutFixedEvaluationTimestamp()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/risk/preauth/"
                        + "store/impl/RedisPreAuthStore.java"));

        assertThat(source).contains(
                "\"schemaVersion\"",
                "\"currentIpDigest\"",
                "\"currentTrustScore\"",
                "\"currentRiskSource\"",
                "\"currentGeoSource\"",
                "\"impossibleTravelEvents\"",
                "\"impossibleTravelCount\"",
                "\"challengeIssuedCount\"",
                "\"challengePassedCount\"",
                "\"activeChallengeNonce\"",
                "\"activeChallengeContextDigest\"",
                "\"webRtcStatus\"",
                "\"webRtcIps\""
        ).doesNotContain(
                "currentIpEvaluatedAt",
                "snapshot.evaluatedAt()");
    }

    @Test
    void newCodeDoesNotDefineIndependentTravelOrChallengeStores()
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

        assertThat(sources)
                .doesNotContain(
                        "interface ImpossibleTravelEventStore",
                        "class RedisImpossibleTravelEventStore",
                        "interface RiskChallengeStore",
                        "class RedisRiskChallengeStore",
                        "interface WebRtcRiskStore",
                        "class RedisWebRtcRiskStore");
    }
}
