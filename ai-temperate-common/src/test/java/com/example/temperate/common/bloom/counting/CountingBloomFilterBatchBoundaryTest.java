package com.example.temperate.common.bloom.counting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来防止旧计数 Bloom 批量入口再次接受无界数据，并明确引导新业务使用版本化引擎。
 */
final class CountingBloomFilterBatchBoundaryTest {

    @Test
    void legacyBatchMethodsAreDeprecatedAndBoundedToOneHundredItems()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/common/bloom/counting/"
                        + "CountingBloomFilter.java"), StandardCharsets.UTF_8);

        assertThat(source)
                .contains("LEGACY_MAX_BATCH_SIZE = 100")
                .contains("requireLegacyBatch(items)")
                .contains("@Deprecated(forRemoval = true)");
    }
}
