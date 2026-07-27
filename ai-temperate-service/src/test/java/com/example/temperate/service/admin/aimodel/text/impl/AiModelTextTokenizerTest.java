package com.example.temperate.service.admin.aimodel.text.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 模型 IK 词元统一小写、去重、稳定排序以及空文本边界。
 */
final class AiModelTextTokenizerTest {

    private final IkAiModelTextTokenizer tokenizer = new IkAiModelTextTokenizer();

    @Test
    void producesDeterministicLowercaseUniqueTokens() {
        List<String> first = tokenizer.tokenize("GPT 模型支持图像，GPT Model");
        List<String> second = tokenizer.tokenize("GPT 模型支持图像，GPT Model");

        assertThat(first)
                .isEqualTo(second)
                .doesNotHaveDuplicates()
                .allMatch(token -> token.equals(token.toLowerCase(java.util.Locale.ROOT)));
        assertThat(first).isSorted();
    }

    @Test
    void returnsEmptyTokensForNullOrBlankText() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("   ")).isEmpty();
    }
}
