package com.example.temperate.service.auth.identity.bloom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证已注册身份 Bloom 参数与项目容量、误判率和批量边界保持一致。
 */
class IdentityPresenceBloomSettingsTest {

    @Test
    void acceptsPlannedOneMillionCounterConfiguration() {
        IdentityPresenceBloomSettings settings =
                new IdentityPresenceBloomSettings(
                        true, 1_000_000, 7, 1, 1_000_000, 500, 256, 100_000);

        assertThat(settings.estimatedFalsePositiveRateAtMaximumElements())
                .isBetween(0.0081D, 0.0083D);
        assertThat(settings.estimatedCounterOccupancyAtMaximumElements())
                .isBetween(0.5033D, 0.5035D);
    }

    @Test
    void rejectsConfigurationThatExceedsProjectBoundaries() {
        assertThatThrownBy(() -> new IdentityPresenceBloomSettings(
                true, 1_000_000, 7, 1, 1_000_000, 2_001, 256, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityPresenceBloomSettings(
                true, 1_000_000, 7, 1, 1_000_000, 500, 8, 1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
