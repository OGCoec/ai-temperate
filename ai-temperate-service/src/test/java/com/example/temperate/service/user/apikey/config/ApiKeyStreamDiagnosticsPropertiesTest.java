package com.example.temperate.service.user.apikey.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证公开 API Key 流式诊断默认全量开启，并锁定窗口、爆发和有界历史的安全默认值。
 */
final class ApiKeyStreamDiagnosticsPropertiesTest {

    @Test
    void defaultsEnableFullSafeDiagnostics() {
        ApiKeyProperties.StreamDiagnostics diagnostics =
                new ApiKeyProperties().getStreamDiagnostics();

        assertThat(diagnostics.isEnabled()).isTrue();
        assertThat(diagnostics.getSampleRate()).isEqualTo(1.0d);
        assertThat(diagnostics.getWindow()).isEqualTo(Duration.ofSeconds(1));
        assertThat(diagnostics.getLogEveryFrames()).isEqualTo(100);
        assertThat(diagnostics.getSilenceThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(diagnostics.getBurstWindow()).isEqualTo(Duration.ofMillis(250));
        assertThat(diagnostics.getBurstFrames()).isEqualTo(50);
        assertThat(diagnostics.getTerminalHistorySize()).isEqualTo(32);
        assertThat(diagnostics.getStackFrameLimit()).isEqualTo(12);
        assertThat(diagnostics.isValid()).isTrue();
    }

    @Test
    void rejectsInvertedTimingAndUnboundedHistory() {
        ApiKeyProperties.StreamDiagnostics diagnostics =
                new ApiKeyProperties.StreamDiagnostics();
        diagnostics.setWindow(Duration.ofSeconds(3));
        diagnostics.setSilenceThreshold(Duration.ofSeconds(2));
        assertThat(diagnostics.isValid()).isFalse();

        diagnostics = new ApiKeyProperties.StreamDiagnostics();
        diagnostics.setTerminalHistorySize(129);
        assertThat(diagnostics.isValid()).isFalse();
    }
}
