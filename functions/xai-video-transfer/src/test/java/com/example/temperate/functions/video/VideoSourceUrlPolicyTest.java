package com.example.temperate.functions.video;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证 FC 在连接前同时拒绝非 HTTPS、非白名单和解析到私网的来源地址。
 */
final class VideoSourceUrlPolicyTest {

    @Test
    void acceptsPublicHttpsAddressFromExactAllowlist() {
        VideoSourceUrlPolicy policy = new VideoSourceUrlPolicy(Set.of("8.8.8.8"));

        assertDoesNotThrow(() -> policy.requireAllowed(
                "https://8.8.8.8/video/result.mp4?signature=opaque"));
    }

    @Test
    void rejectsLoopbackAndNonHttpsSources() {
        VideoSourceUrlPolicy policy = new VideoSourceUrlPolicy(Set.of(
                "127.0.0.1", "8.8.8.8"));

        assertThrows(IllegalArgumentException.class, () ->
                policy.requireAllowed("https://127.0.0.1/video.mp4"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.requireAllowed("http://8.8.8.8/video.mp4"));
    }
}
