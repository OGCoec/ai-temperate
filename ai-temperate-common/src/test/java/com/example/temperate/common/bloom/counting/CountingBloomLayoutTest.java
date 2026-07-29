package com.example.temperate.common.bloom.counting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证计数布隆过滤器的分片布局、确定性哈希和参数安全边界。
 */
class CountingBloomLayoutTest {

    @Test
    void createsDeterministicUniquePositionsInsideConfiguredBuckets() {
        CountingBloomLayout layout =
                new CountingBloomLayout(2_500_000, 7, 1, 1_000_000);

        List<CountingBloomPosition> first = layout.positions("protected-email");
        List<CountingBloomPosition> second = layout.positions("protected-email");

        assertThat(first).hasSize(7).containsExactlyElementsOf(second);
        assertThat(first).doesNotHaveDuplicates();
        assertThat(first).allSatisfy(position -> {
            assertThat(position.bucketNumber()).isBetween(0, 2);
            assertThat(position.byteOffset()).isBetween(0, 999_999);
        });
        assertThat(layout.bucketCount()).isEqualTo(3);
        assertThat(layout.bucketByteLength(0)).isEqualTo(1_000_000);
        assertThat(layout.bucketByteLength(2)).isEqualTo(500_000);
    }

    @Test
    void rejectsUnsafeCounterAndBucketConfiguration() {
        assertThatThrownBy(() -> new CountingBloomLayout(1_000_000, 3, 1, 1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountingBloomLayout(1_000_000, 7, 3, 1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountingBloomLayout(1_000_000, 7, 1, 500_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountingBloomLayout(8_000_000, 7, 1, 5_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
