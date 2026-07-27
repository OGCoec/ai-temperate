package com.example.temperate.service.admin.aimodel.icon.image;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证 AVIF 原生 Reader 缺失时应用启动检查明确失败，而不是退化为魔数检查。
 */
final class AiModelIconRequiredDecoderVerifierTest {

    @Test
    void failsStartupWhenAvifReaderIsUnavailable() {
        assertThatThrownBy(() ->
                AiModelIconRequiredDecoderVerifier.verifyAvifReaderAvailable(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AVIF");
    }

    @Test
    void acceptsAvailableAvifReader() {
        assertThatCode(() ->
                AiModelIconRequiredDecoderVerifier.verifyAvifReaderAvailable(true))
                .doesNotThrowAnyException();
    }
}
